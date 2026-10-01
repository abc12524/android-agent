package com.androidagent.data

import java.util.Calendar
import java.util.TimeZone

/**
 * DeepSeek 计费：工作日峰谷单价与费用计算（对标 shell-tool/core/billing.py）
 *
 * 单价单位：元 / 百万 tokens
 * 高峰时段（北京时间）：周一至周五 09:00-12:00、14:00-18:00
 */
object Billing {

    data class Price(val hit: Double, val miss: Double, val out: Double)

    private val PRICING: Map<String, Map<Boolean, Price>> = mapOf(
        "flash" to mapOf(
            true to Price(0.04, 2.0, 8.0),
            false to Price(0.02, 1.0, 4.0),
        ),
        "pro" to mapOf(
            true to Price(0.30, 9.0, 27.0),
            false to Price(0.15, 4.5, 13.5),
        ),
    )

    private val BEIJING = TimeZone.getTimeZone("Asia/Shanghai")

    data class Cost(val hit: Double, val miss: Double, val out: Double) {
        val total: Double get() = hit + miss + out
    }

    /** 按模型名归族：含 pro 记为 pro，其余（flash / v4-flash 等）按 flash 计费 */
    fun modelFamily(model: String? = null): String =
        if ((model ?: AppPreferences.deepSeekModel).lowercase().contains("pro")) "pro" else "flash"

    /** 按北京时间判断是否为工作日高峰时段 */
    fun isPeak(whenMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(BEIJING)
        cal.timeInMillis = whenMs
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val morning = minutes in 9 * 60 until 12 * 60
        val afternoon = minutes in 14 * 60 until 18 * 60
        return morning || afternoon
    }

    /** 计算用量费用；whenMs 为该次用量发生时刻，用于判定峰谷价 */
    fun computeCost(
        cacheHit: Int,
        cacheMiss: Int,
        output: Int,
        model: String? = null,
        whenMs: Long = System.currentTimeMillis(),
    ): Cost {
        val price = PRICING[modelFamily(model)]?.get(isPeak(whenMs)) ?: Price(0.0, 0.0, 0.0)
        return Cost(
            hit = cacheHit / 1_000_000.0 * price.hit,
            miss = cacheMiss / 1_000_000.0 * price.miss,
            out = output / 1_000_000.0 * price.out,
        )
    }
}
