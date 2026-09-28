-- ============================================================================
-- 会话归属（owner 单写者租约）· 2026-09-28
--
-- 背景：单人多设备下「两端各发几条 → 判定分叉 → Fork 出副本」会自激增殖。
-- 解法是给会话一个归属：有主时只有主能写，非主要写必须先显式接管（epoch+1）。
--
-- 三列语义：
--   owner_device  ''       = 无主（谁都能写，写入即占位）→ 存量行零回填
--   owner_epoch   世代号   每次显式接管 +1；裁决只看它的大小，**不看墙钟**
--   owner_hlc     接管时刻的逻辑时钟，仅审计 / 后续 TTL 用，不参与裁决
--
-- 幂等：全部 IF NOT EXISTS / CREATE OR REPLACE，可重复执行。
-- 回滚：见文件末尾（三列可留，函数回滚到旧体即可）。
-- ============================================================================

begin;

-- ---------------------------------------------------------------- 1) 加列
alter table conversations add column if not exists owner_device text   not null default '';
alter table conversations add column if not exists owner_epoch  bigint not null default 0;
alter table conversations add column if not exists owner_hlc    bigint not null default 0;

-- ---------------------------------------------------------------- 2) 整包 UPSERT
--
-- 相对旧体的唯一语义变化：所有权世代参与守卫。
--   旧：新赢旧（updated_at / last_device 字典序兜底）
--   新：**epoch 大的赢**；epoch 相等才回落到 updated_at / last_device
--
-- 注意 `excluded.owner_epoch < conversations.owner_epoch` 会走到
-- 「三个分支全不命中 → 整条跳过」，这正是我们要的：过期的 owner 说话不算数。
create or replace function public.jf_upsert_conversations(payload jsonb)
 returns integer
 language plpgsql
as $function$
declare
  n integer := 0;
  r record;
begin
  for r in
    select * from jsonb_to_recordset(payload) as x(
      id text, title text, updated_at bigint, deleted integer,
      sha text, data text, last_device text, storage text,
      owner_device text, owner_epoch bigint, owner_hlc bigint)
  loop
    insert into conversations(
      id, title, updated_at, version, deleted, sha, data, last_device, storage,
      owner_device, owner_epoch, owner_hlc)
    values (
      r.id, r.title, coalesce(r.updated_at, 0), 1, coalesce(r.deleted, 0),
      coalesce(r.sha, ''), coalesce(r.data, ''), coalesce(r.last_device, ''),
      coalesce(r.storage, 'supabase'),
      coalesce(r.owner_device, ''), coalesce(r.owner_epoch, 0), coalesce(r.owner_hlc, 0))
    on conflict (id) do update
      set title        = excluded.title,
          updated_at   = excluded.updated_at,
          deleted      = excluded.deleted,
          sha          = excluded.sha,
          data         = excluded.data,
          last_device  = excluded.last_device,
          storage      = excluded.storage,
          owner_device = excluded.owner_device,
          owner_epoch  = excluded.owner_epoch,
          owner_hlc    = excluded.owner_hlc,
          version      = conversations.version + 1
      where conversations.sha <> excluded.sha
        and not (conversations.deleted = 1 and excluded.deleted = 0)
        and ( excluded.owner_epoch > conversations.owner_epoch
           or ( excluded.owner_epoch = conversations.owner_epoch
                and ( excluded.updated_at > conversations.updated_at
                   or ( excluded.updated_at = conversations.updated_at
                        and excluded.last_device > conversations.last_device ) ) ) );
    if found then n := n + 1; end if;
  end loop;
  return n;
end;
$function$;

-- ---------------------------------------------------------------- 3) 水位上行
--
-- 这条路径**仍然没有整体守卫**（node-only 模式下 sha 恒为空串，加 sha 守卫会
-- 让水位第二次起就推不动，见函数头注释）。
--
-- 但 owner 三列必须单独守卫：否则一台不认识新列的旧客户端、或一台 epoch 落后的
-- 设备随便 bump 一次水位，就能把归属从别人手里偷走。
-- 规则：owner 只在 epoch 不降时前移；updated_at 照旧无条件上行。
create or replace function public.jf_bump_conversation_meta(payload jsonb)
 returns integer
 language plpgsql
as $function$
declare
  n integer := 0;
  r record;
begin
  for r in
    select * from jsonb_to_recordset(payload) as x(
      id text, title text, updated_at bigint, last_device text,
      owner_device text, owner_epoch bigint, owner_hlc bigint)
  loop
    update conversations
       set title        = r.title,
           updated_at   = coalesce(r.updated_at, 0),
           deleted      = 0,
           last_device  = coalesce(r.last_device, ''),
           owner_device = case when coalesce(r.owner_epoch, 0) >= conversations.owner_epoch
                               then coalesce(r.owner_device, '')
                               else conversations.owner_device end,
           owner_epoch  = greatest(conversations.owner_epoch, coalesce(r.owner_epoch, 0)),
           owner_hlc    = case when coalesce(r.owner_epoch, 0) >= conversations.owner_epoch
                               then coalesce(r.owner_hlc, 0)
                               else conversations.owner_hlc end
     where id = r.id;
    if found then
      n := n + 1;
    else
      insert into conversations(
        id, title, updated_at, deleted, sha, data, last_device, storage,
        owner_device, owner_epoch, owner_hlc)
      values (r.id, r.title, coalesce(r.updated_at, 0), 0, '', '',
              coalesce(r.last_device, ''), 'supabase',
              coalesce(r.owner_device, ''), coalesce(r.owner_epoch, 0), coalesce(r.owner_hlc, 0));
      n := n + 1;
    end if;
  end loop;
  return n;
end;
$function$;

commit;

-- ============================================================================
-- 回滚（如需）：
--   begin;
--   -- 函数体回滚到 2026-09-28 之前的版本（去掉 owner_* 三列的 select / set / values）
--   -- 三列本身可以保留：默认值 ''/0 对旧函数完全透明
--   commit;
-- 三列不必 DROP：留着不占空间，且旧代码读不到它们（select 列表是显式的）。
-- ============================================================================
