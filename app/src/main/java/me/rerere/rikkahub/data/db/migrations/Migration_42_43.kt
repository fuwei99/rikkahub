package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * agent_session 提前结束计数（v42 → v43，2026-08-14 需求）。
 *
 * 子代理在汇报结果前就正常结束对话（没报错）时，主代理查询状态仍显示 running，
 * 既无终态也无提醒。新增 premature_end_count：
 * - 前 N 次（AgentLimits.MAX_PREMATURE_END_REMINDERS）向子代理本人发系统提醒继续；
 * - 超过后升级为系统消息告知主代理，状态落 stopped。
 *
 * 计数必须落库（收敛设计 §10「能落库的绝不只存内存」）：重启后不能丢
 * 「已经提醒过几次」——否则每次重启都会把已升级的会话重新骚扰一轮。
 */
val Migration_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_session ADD COLUMN premature_end_count INTEGER NOT NULL DEFAULT 0")
    }
}
