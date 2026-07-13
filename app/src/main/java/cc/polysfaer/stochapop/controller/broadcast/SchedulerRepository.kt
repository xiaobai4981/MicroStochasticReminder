package cc.polysfaer.stochapop.controller.broadcast

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import cc.polysfaer.stochapop.data.reminder.Reminder
import cc.polysfaer.stochapop.data.reminder.ReminderSettings
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.random.Random


const val INTENT_REMINDER_ID = "REMINDER_ID"
const val INTENT_NOTIFICATION_ID = "NOTIFICATION_ID"


class SchedulerRepository(
    private val context: Context
) {
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private val logTag: String
        get() = javaClass.simpleName

    /**
     * 为一个 Reminder 创建所有闹钟。
     *
     * 可在以下情况调用：
     * 1. 创建 Reminder
     * 2. 编辑 Reminder
     * 3. 设备重启
     * 4. 时区或系统时间改变
     */
    fun scheduleReminderAlarms(reminder: Reminder) {
        validateReminder(reminder)

        if (!reminder.enabled) {
            Log.d(
                logTag,
                "Reminder ${reminder.id} is disabled, scheduling skipped."
            )
            return
        }

        if (reminder.useRandomRange) {
            scheduleAllRandomAlarms(reminder)
        } else {
            scheduleFixedAlarm(reminder)
        }
    }

    /**
     * 在某个通知触发后，安排它的下一次通知。
     *
     * notificationId：
     * 固定提醒通常是 0。
     * 随机提醒是 0 until notificationCount。
     */
    fun scheduleReminderNextSingleAlarm(
        reminder: Reminder,
        notificationId: Int
    ) {
        validateReminder(reminder)
        validateNotificationId(notificationId)

        if (!reminder.enabled) {
            Log.d(
                logTag,
                "Reminder ${reminder.id} is disabled, next alarm not scheduled."
            )
            return
        }

        if (reminder.useRandomRange) {
            require(notificationId < reminder.notificationCount) {
                "notificationId $notificationId exceeds notificationCount " +
                        "${reminder.notificationCount}."
            }

            val localTimeSegment = getTimeSegmentInMinutes(
                startTime = reminder.startTime,
                endTime = reminder.endTime,
                segmentCount = reminder.notificationCount
            )

            scheduleRandomNotificationAlarm(
                reminderId = reminder.id,
                startTime = reminder.startTime,
                selectedDays = reminder.selectedDays,
                localTimeSegment = localTimeSegment,
                notificationId = notificationId
            )
        } else {
            val triggerTime = findNextTriggerDateTime(
                startTime = reminder.startTime,
                selectedDays = reminder.selectedDays
            )

            scheduleNotificationAlarm(
                reminderId = reminder.id,
                triggerTime = triggerTime,
                notificationId = 0,
                useExact = canScheduleExactAlarms()
            )
        }
    }

    /**
     * 取消一个通知闹钟。
     */
    fun cancelNotificationAlarm(
        reminderId: Int,
        notificationId: Int
    ) {
        validateNotificationId(notificationId)

        val intent = createNotificationIntent(
            reminderId = reminderId,
            notificationId = notificationId
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getRequestCode(reminderId, notificationId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()

            Log.d(
                logTag,
                "Cancelled reminder ${reminderId}_$notificationId"
            )
        }
    }

    /**
     * 批量取消通知。
     *
     * endNotificationIdExclusive 不包含在取消范围中。
     *
     * 例如：
     * cancelNotificationAlarms(reminderId, 0, 5)
     * 会取消 notificationId 0、1、2、3、4。
     */
    fun cancelNotificationAlarms(
        reminderId: Int,
        firstNotificationId: Int,
        endNotificationIdExclusive: Int
    ) {
        require(firstNotificationId >= 0) {
            "firstNotificationId must be greater than or equal to 0."
        }

        require(
            endNotificationIdExclusive <=
                    ReminderSettings.RANDOM_NOTIFICATION_COUNT_LIMIT
        ) {
            "endNotificationIdExclusive exceeds RANDOM_NOTIFICATION_COUNT_LIMIT."
        }

        if (firstNotificationId >= endNotificationIdExclusive) {
            return
        }

        for (
        notificationId in
        firstNotificationId until endNotificationIdExclusive
        ) {
            cancelNotificationAlarm(
                reminderId = reminderId,
                notificationId = notificationId
            )
        }
    }

    /**
     * 当前应用是否可以使用精确闹钟。
     *
     * Android 12 以下不需要特殊授权。
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 创建跳转到“闹钟和提醒”特殊权限页面的 Intent。
     *
     * 已经拥有权限或者 Android 12 以下时返回 null。
     *
     * 应该由 Activity 调用 startActivity()，
     * 不建议 Repository 自己打开页面。
     */
    fun buildExactAlarmPermissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }

        if (alarmManager.canScheduleExactAlarms()) {
            return null
        }

        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * 创建一个固定时间提醒。
     */
    private fun scheduleFixedAlarm(reminder: Reminder) {
        val triggerTime = findNextTriggerDateTime(
            startTime = reminder.startTime,
            selectedDays = reminder.selectedDays
        )

        /*
         * 固定时间提醒在拥有权限时使用精确闹钟。
         *
         * 是否使用精确闹钟不再由声音和振动决定，
         * 因为声音、振动只是通知表现形式。
         */
        scheduleNotificationAlarm(
            reminderId = reminder.id,
            triggerTime = triggerTime,
            notificationId = 0,
            useExact = canScheduleExactAlarms()
        )
    }

    /**
     * 创建一个 Reminder 下的所有随机提醒。
     */
    private fun scheduleAllRandomAlarms(reminder: Reminder) {
        val localTimeSegment = getTimeSegmentInMinutes(
            startTime = reminder.startTime,
            endTime = reminder.endTime,
            segmentCount = reminder.notificationCount
        )

        for (notificationId in 0 until reminder.notificationCount) {
            scheduleRandomNotificationAlarm(
                reminderId = reminder.id,
                startTime = reminder.startTime,
                selectedDays = reminder.selectedDays,
                localTimeSegment = localTimeSegment,
                notificationId = notificationId
            )
        }
    }

    /**
     * 安排一个随机时间通知。
     *
     * 随机提醒使用 setAndAllowWhileIdle，不使用精确闹钟。
     */
    private fun scheduleRandomNotificationAlarm(
        reminderId: Int,
        startTime: LocalTime,
        selectedDays: Set<DayOfWeek>,
        localTimeSegment: Double,
        notificationId: Int
    ) {
        validateNotificationId(notificationId)

        val minOffset = (notificationId * localTimeSegment).toLong()
        val maxOffset = ((notificationId + 1) * localTimeSegment).toLong()

        /*
         * 避免起始值和结束值相同，导致 Random.nextLong() 抛异常。
         */
        val safeMaxOffset = maxOffset.coerceAtLeast(minOffset + 1L)

        val randomMinuteOffset = Random.nextLong(
            from = minOffset,
            until = safeMaxOffset
        )

        /*
         * 先确定随机偏移，再根据随机偏移寻找下一个合法日期。
         *
         * 原代码先使用 minOffset 查找日期，
         * 后面又添加一次随机 offset，会造成偏移被重复计算。
         */
        val triggerTime = findNextTriggerDateTime(
            startTime = startTime,
            selectedDays = selectedDays,
            minuteOffset = randomMinuteOffset
        )

        scheduleNotificationAlarm(
            reminderId = reminderId,
            triggerTime = triggerTime,
            notificationId = notificationId,
            useExact = false
        )
    }

    /**
     * 创建并登记一个 AlarmManager 闹钟。
     */
    private fun scheduleNotificationAlarm(
        reminderId: Int,
        triggerTime: LocalDateTime,
        notificationId: Int,
        useExact: Boolean
    ) {
        validateNotificationId(notificationId)

        val intent = createNotificationIntent(
            reminderId = reminderId,
            notificationId = notificationId
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getRequestCode(reminderId, notificationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTimeMs = triggerTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val scheduledExactly = setAlarmSafely(
            triggerTimeMs = triggerTimeMs,
            pendingIntent = pendingIntent,
            useExact = useExact
        )

        val alarmType = if (scheduledExactly) {
            "exact"
        } else {
            "inexact"
        }

        Log.d(
            logTag,
            "Scheduled $alarmType reminder " +
                    "${reminderId}_$notificationId at $triggerTime"
        )
    }

    /**
     * 安全地登记闹钟。
     *
     * 即使 canScheduleExactAlarms() 检查通过，
     * 权限也可能在实际调用前被用户撤销。
     *
     * 因此仍然捕获 SecurityException，并降级为非精确闹钟。
     *
     * 返回值：
     * true  = 成功使用精确闹钟
     * false = 使用了非精确闹钟
     */
    @SuppressLint("MissingPermission")
    private fun setAlarmSafely(
        triggerTimeMs: Long,
        pendingIntent: PendingIntent,
        useExact: Boolean
    ): Boolean {
        if (useExact) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                }

                return true
            } catch (exception: SecurityException) {
                Log.w(
                    logTag,
                    "Exact alarm permission unavailable. " +
                            "Falling back to an inexact alarm.",
                    exception
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMs,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMs,
                pendingIntent
            )
        }

        return false
    }

    /**
     * 创建 NotificationReceiver 使用的 Intent。
     */
    private fun createNotificationIntent(
        reminderId: Int,
        notificationId: Int
    ): Intent {
        return Intent(context, NotificationReceiver::class.java).apply {
            putExtra(INTENT_REMINDER_ID, reminderId)
            putExtra(INTENT_NOTIFICATION_ID, notificationId)
        }
    }

    /**
     * 将随机时间范围分割成指定数量的时间段。
     */
    private fun getTimeSegmentInMinutes(
        startTime: LocalTime,
        endTime: LocalTime,
        segmentCount: Int
    ): Double {
        require(segmentCount > 0) {
            "segmentCount must be greater than 0."
        }

        val duration = Duration.between(startTime, endTime)

        val normalizedDuration = if (duration.isNegative) {
            duration.plusDays(1)
        } else {
            duration
        }

        val rangeMinutes = normalizedDuration.toMinutes()

        require(rangeMinutes > 0) {
            "Random reminder start time and end time cannot be identical."
        }

        return rangeMinutes.toDouble() / max(1, segmentCount)
    }

    /**
     * 查找下一个符合星期设置且处于未来的触发时间。
     *
     * selectedDays 对应的是时间范围开始的日期。
     *
     * 如果随机范围跨过午夜，例如：
     * 周一 23:00 到周二 01:00，
     * 那么它仍然属于“周一”的提醒范围。
     */
    private fun findNextTriggerDateTime(
        startTime: LocalTime,
        selectedDays: Set<DayOfWeek>,
        minuteOffset: Long = 0L
    ): LocalDateTime {
        require(selectedDays.isNotEmpty()) {
            "No days were selected."
        }

        val now = LocalDateTime.now()
        val firstCandidate = LocalDate.now().atTime(startTime)

        return generateSequence(firstCandidate) {
            it.plusDays(1)
        }.first { candidateStartTime ->
            val finalTriggerTime =
                candidateStartTime.plusMinutes(minuteOffset)

            candidateStartTime.dayOfWeek in selectedDays &&
                    finalTriggerTime.isAfter(now)
        }.plusMinutes(minuteOffset)
    }

    /**
     * Reminder ID 和 notificationId 组合成唯一 requestCode。
     */
    private fun getRequestCode(
        reminderId: Int,
        notificationId: Int
    ): Int {
        require(reminderId > 0) {
            "Invalid reminderId: $reminderId"
        }

        validateNotificationId(notificationId)

        return reminderId *
                ReminderSettings.RANDOM_NOTIFICATION_COUNT_LIMIT +
                notificationId
    }

    private fun validateReminder(reminder: Reminder) {
        require(reminder.id > 0) {
            "Invalid reminderId: ${reminder.id}"
        }

        require(reminder.selectedDays.isNotEmpty()) {
            "No days were selected."
        }

        if (reminder.useRandomRange) {
            require(reminder.notificationCount > 0) {
                "notificationCount must be greater than 0."
            }

            require(
                reminder.notificationCount <=
                        ReminderSettings.RANDOM_NOTIFICATION_COUNT_LIMIT
            ) {
                "notificationCount ${reminder.notificationCount} exceeds " +
                        "RANDOM_NOTIFICATION_COUNT_LIMIT " +
                        "${ReminderSettings.RANDOM_NOTIFICATION_COUNT_LIMIT}."
            }
        }
    }

    private fun validateNotificationId(notificationId: Int) {
        require(
            notificationId in
                    0 until ReminderSettings.RANDOM_NOTIFICATION_COUNT_LIMIT
        ) {
            "Invalid notificationId: $notificationId"
        }
    }
}