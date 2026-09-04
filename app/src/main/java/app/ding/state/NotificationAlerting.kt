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

/**
 * The two decisions about sound that the notification builder makes, kept here as
 * plain functions so they can be checked without a device. Nothing in this file
 * imports Android.
 */

/**
 * Whether a notification of this kind makes a sound and vibrates. A delivery and
 * a nag alert; a re-show, which only puts a notification back on screen after the
 * process restarted, is silent.
 */
fun notificationAlerts(kind: NotificationKind): Boolean = kind != NotificationKind.RESHOW

/**
 * Whether the notification may alert only the first time it is posted under its
 * reminder id.
 *
 * Every notification for a reminder is posted under the reminder's id, so a later
 * one replaces the one on screen. Android stays quiet for such a replacement when
 * only-alert-once is on, which is what a nag must not do: a nag replaces a
 * notification that is still showing, and being heard is the whole point of it.
 * A delivery keeps only-alert-once as defence in depth against a duplicate
 * delivery alerting twice, and a re-show is silent either way.
 */
fun notificationAlertsOnlyOnce(kind: NotificationKind): Boolean = kind != NotificationKind.NAG
