-- ============================================================================
-- 会话元数据列 meta · 2026-09-28
--
-- 背景（现场）：
--   node-only 上行只写 conversations 的 title / updated_at，会话正文走 conv_nodes，
--   `conversations.data` 恒为空串。于是对端 `pullNodeColdStart` 重建会话时，
--   assistantId / 模型 / 文件夹 / 工作区**无处可取**，只能回落 Room 列默认值
--   `assistant_id = '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'`（默认助手）。
--   而会话列表是**按助手过滤**的 —— 结果是「数据一条不丢，列表里一条看不见」。
--
--   2026-09-28 实测：k70 推上去的 21 个会话在 MatePad 全挂到默认助手名下，
--   用户视角就是「同步了但看不到」。
--
-- 解法：
--   conversations 加一列 meta，存一段极小的 JSON（只放元数据，绝不放消息）：
--     {"assistantId":"…","modelId":"…","folderId":"…","workspaceId":"…"}
--   它只随「水位上行」那条路走（jf_bump_conversation_meta），data / sha 语义不变，
--   仍然恒为空串 —— 读侧「data 为空 → 走 node 通道」的判据一个字不改。
--
-- 空串语义：meta = '' 表示「本次没带元数据」，**原地保留旧值**。
--   这样一台还没有正确元数据的设备（比如冷启动重建后的平板）随便 bump 一次水位，
--   也不会把另一台设备推上来的正确元数据冲掉。
--
-- 幂等：全部 add column if not exists / create or replace，可重复执行。
-- 回滚：见文件末尾（列可留，函数回滚到旧体即可）。
-- ============================================================================

begin;

-- ---------------------------------------------------------------- 1) 加列
alter table conversations add column if not exists meta text not null default '';

-- ---------------------------------------------------------------- 2) 水位上行
--
-- 相对旧体的唯一变化：多带一列 meta，且 meta 走「非空才前移」的守卫。
-- owner 三列的语义逐字不变（epoch 不降才前移）。
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
      owner_device text, owner_epoch bigint, owner_hlc bigint,
      meta text)
  loop
    update conversations
       set title        = r.title,
           updated_at   = coalesce(r.updated_at, 0),
           deleted      = 0,
           last_device  = coalesce(r.last_device, ''),
           -- '' = 本次没带元数据 → 保留旧值（旧客户端 / 冷启动端不得冲掉正确元数据）
           meta         = case when coalesce(r.meta, '') <> '' then r.meta else conversations.meta end,
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
        owner_device, owner_epoch, owner_hlc, meta)
      values (r.id, r.title, coalesce(r.updated_at, 0), 0, '', '',
              coalesce(r.last_device, ''), 'supabase',
              coalesce(r.owner_device, ''), coalesce(r.owner_epoch, 0), coalesce(r.owner_hlc, 0),
              coalesce(r.meta, ''));
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
--   -- jf_bump_conversation_meta 回滚到 2026-09-28 之前的版本（去掉 meta 的
--   -- select / set / values 三处）。meta 列本身可以保留：默认值 '' 对旧函数透明。
--   commit;
-- ============================================================================
