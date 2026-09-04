/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package app.ding.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The notification builder itself needs a device, so the two decisions it makes
 * about sound live in `NotificationAlerting.kt` and are checked here instead.
 */
class NotificationAlertingTest : FunSpec({

    test("a delivery and a nag alert, a re-show is silent") {
        notificationAlerts(NotificationKind.DELIVER) shouldBe true
        notificationAlerts(NotificationKind.NAG) shouldBe true
        notificationAlerts(NotificationKind.RESHOW) shouldBe false
    }

    test("a nag may alert again under a notification id that is already on screen") {
        // Only-alert-once silences a notification that replaces one still showing,
        // which is exactly what a nag does, so a nag must not have it.
        notificationAlertsOnlyOnce(NotificationKind.NAG) shouldBe false
        notificationAlertsOnlyOnce(NotificationKind.DELIVER) shouldBe true
        notificationAlertsOnlyOnce(NotificationKind.RESHOW) shouldBe true
    }
})
