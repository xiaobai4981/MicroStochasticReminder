package cc.polysfaer.stochapop.data

import android.content.Context
import cc.polysfaer.stochapop.R
import cc.polysfaer.stochapop.data.reminder.Reminder
import cc.polysfaer.stochapop.ui.screens.reminder.ReminderDetails
import cc.polysfaer.stochapop.ui.screens.reminder.toReminder
import java.time.DayOfWeek
import java.time.LocalTime

object DataSource {

    fun getReminders(context: Context): List<Reminder> {
        return listOf(
            ReminderDetails(
                id = 1,
                title = context.getString(R.string.tuto_4_title),
                message = "◝(ᵔᗜᵔ)◜ ♡ ",
                enabled = true,
                useRandomRange = false,
                hasSound = true,
                hasVibration = true,
                startTime = LocalTime.now().plusMinutes(1)
            ),
        ).map { it.toReminder() }
    }
}
