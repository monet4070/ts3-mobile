package io.github.ts3mobile.app.ui

import io.github.ts3mobile.app.R

internal enum class GuideRoute(
    val anchor: GuideAnchor,
    val settings: SettingsDestination? = null,
    val tab: SessionTab? = null,
    val fallback: GuideAnchor? = null,
    val settingsEntry: GuideAnchor? = null,
) {
    BOOKMARKS(GuideAnchor.BOOKMARK_ADD),
    IDENTITY(GuideAnchor.IDENTITY_OPERATIONS, settingsEntry = GuideAnchor.SETTINGS_IDENTITY),
    MICROPHONE(GuideAnchor.TEST_MIC, SettingsDestination.MICROPHONE, settingsEntry = GuideAnchor.SETTINGS_MICROPHONE),
    PLAYBACK(GuideAnchor.PLAYBACK_PROFILE, SettingsDestination.PLAYBACK, settingsEntry = GuideAnchor.SETTINGS_PLAYBACK),
    FLOATING_SETUP(GuideAnchor.PTT_AUTOSHOW, settingsEntry = GuideAnchor.SETTINGS_FLOATING),
    FLOATING_USE(GuideAnchor.PTT_SHOW, settingsEntry = GuideAnchor.SETTINGS_FLOATING),
    CHAT(GuideAnchor.CHAT_CONVERSATIONS, tab = SessionTab.CHAT),
    USERS(GuideAnchor.MEMBER_ACTIONS, tab = SessionTab.PARTICIPANTS, fallback = GuideAnchor.USERS_TAB),
    FILES(GuideAnchor.FILE_ENTRY, fallback = GuideAnchor.FILE_LIST),
    ATTACHMENT(GuideAnchor.CHAT_MESSAGES, tab = SessionTab.CHAT),
    HISTORY(GuideAnchor.HISTORY_TOGGLE, SettingsDestination.CHAT_HISTORY, settingsEntry = GuideAnchor.SETTINGS_HISTORY),
    NOTIFICATIONS(GuideAnchor.NOTIFICATION_SETTINGS, settingsEntry = GuideAnchor.NOTIFICATION_SETTINGS),
    DIAGNOSTICS(GuideAnchor.DIAGNOSTIC_MARK, SettingsDestination.DIAGNOSTICS, settingsEntry = GuideAnchor.SETTINGS_DIAGNOSTICS),
    ;

    val step: GuideStep
        get() = guideTopics.first { it.route == this }

    val menuLabel: Int?
        get() =
            when {
                settingsEntry != null -> R.string.settings_title
                this == BOOKMARKS -> R.string.bookmarks_title
                this == FILES -> R.string.channel_files_probe_title
                else -> null
            }
}

internal data class GuideNavigation(
    val route: GuideRoute,
    val onTabSelected: (SessionTab) -> Unit,
    val onDestinationOpened: () -> Unit,
)

internal val SessionTab.guideEntry: GuideAnchor
    get() =
        when (this) {
            SessionTab.CHANNELS -> GuideAnchor.CHANNELS_TAB
            SessionTab.PARTICIPANTS -> GuideAnchor.USERS_TAB
            SessionTab.CHAT -> GuideAnchor.CHAT_TAB
        }

internal data class GuideStep(
    val title: Int,
    val description: Int,
    val icon: UiIcon,
    val anchor: GuideAnchor? = null,
    val route: GuideRoute? = null,
    val needsConnection: Boolean = false,
)

internal val guideTopics =
    listOf(
        GuideStep(R.string.guide_connect_title, R.string.guide_connect_body, UiIcon.Server, GuideAnchor.SERVER),
        GuideStep(R.string.guide_invite_title, R.string.guide_invite_body, UiIcon.Copy, GuideAnchor.INVITE),
        GuideStep(
            R.string.guide_bookmarks_title,
            R.string.guide_bookmarks_body,
            UiIcon.Bookmark,
            GuideAnchor.BOOKMARKS,
            GuideRoute.BOOKMARKS,
        ),
        GuideStep(R.string.guide_identity_title, R.string.guide_identity_body, UiIcon.Shield, route = GuideRoute.IDENTITY),
        GuideStep(R.string.guide_trial_title, R.string.guide_trial_body, UiIcon.Audio, route = GuideRoute.MICROPHONE),
        GuideStep(R.string.guide_playback_title, R.string.guide_playback_body, UiIcon.Headphones, route = GuideRoute.PLAYBACK),
        GuideStep(R.string.guide_overlay_title, R.string.guide_overlay_body, UiIcon.FloatingPtt, route = GuideRoute.FLOATING_SETUP),
        GuideStep(R.string.guide_overlay_use_title, R.string.guide_overlay_use_body, UiIcon.Radio, route = GuideRoute.FLOATING_USE),
        GuideStep(R.string.guide_chat_title, R.string.guide_chat_body, UiIcon.Chat, route = GuideRoute.CHAT, needsConnection = true),
        GuideStep(R.string.guide_member_title, R.string.guide_member_body, UiIcon.User, route = GuideRoute.USERS, needsConnection = true),
        GuideStep(R.string.guide_files_title, R.string.guide_files_body, UiIcon.Folder, route = GuideRoute.FILES, needsConnection = true),
        GuideStep(
            R.string.guide_attachment_title,
            R.string.guide_attachment_body,
            UiIcon.Download,
            route = GuideRoute.ATTACHMENT,
            needsConnection = true,
        ),
        GuideStep(R.string.guide_history_title, R.string.guide_history_body, UiIcon.History, route = GuideRoute.HISTORY),
        GuideStep(R.string.guide_notifications_title, R.string.guide_notifications_body, UiIcon.Poke, route = GuideRoute.NOTIFICATIONS),
        GuideStep(R.string.guide_leave_title, R.string.guide_leave_body, UiIcon.Power, GuideAnchor.DISCONNECT, needsConnection = true),
        GuideStep(R.string.guide_diagnostics_title, R.string.guide_diagnostics_body, UiIcon.Activity, route = GuideRoute.DIAGNOSTICS),
    )
