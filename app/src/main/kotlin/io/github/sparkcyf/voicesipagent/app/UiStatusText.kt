package io.github.sparkcyf.voicesipagent.app

import android.content.Context

/** Display-only adapter for legacy status strings shared with logs and ADB. Never feeds the runtime. */
internal fun Context.displayStatus(text: String): String = localizeStatus(text) { id, args ->
    getString(id, *args.toTypedArray())
}

/** Exact matches and anchored, known templates only; unknown diagnostics and opaque values survive. */
internal fun localizeStatus(text: String, resolve: (Int, List<String>) -> String): String =
    localizeStatus(text, resolve, 0)

private fun localizeStatus(text: String, resolve: (Int, List<String>) -> String, depth: Int): String {
    if (depth > 8) return text
    fixedStatusResources[text]?.let { return resolve(it, emptyList()) }
    for (template in statusPrefixes) {
        if (text.startsWith(template.prefix)) {
            val suffix = text.removePrefix(template.prefix)
            return resolve(template.resource, listOf(if (template.nested)
                localizeStatus(suffix, resolve, depth + 1) else suffix))
        }
    }
    registeredBuiltin.matchEntire(text)?.let {
        return resolve(R.string.status_registered_1_s_s, listOf(it.groupValues[1]))
    }
    registeredPjsua2.matchEntire(text)?.let {
        return resolve(R.string.status_registered_pjsua2_1_ss, listOf(it.groupValues[1]))
    }
    if (callSummary.matches(text)) {
        return text.split(", ").joinToString(", ") { call ->
            val (kind, state) = call.split(": ", limit = 2)
            val stateText = resolve(callStateResources.getValue(state), emptyList())
            resolve(if (kind == "Gateway call") R.string.status_gateway_call_1_s else R.string.status_other_call_1_s,
                listOf(stateText))
        }
    }
    return text
}

private data class StatusPrefix(val prefix: String, val resource: Int, val nested: Boolean)
private val registeredBuiltin = Regex("registered \\((\\d+) s\\)")
private val registeredPjsua2 = Regex("registered \\(pjsua2, (\\d+)s\\)")
private val callSummary = Regex("(?:Gateway call|Other call): (?:incoming|active|held|connecting)(?:, (?:Gateway call|Other call): (?:incoming|active|held|connecting))*")
private val callStateResources = mapOf(
    "incoming" to R.string.status_incoming,
    "active" to R.string.status_active,
    "held" to R.string.status_held,
    "connecting" to R.string.status_connecting,
)
private val fixedStatusResources = mapOf(
    "Changing the default dialer" to R.string.ui_changing_the_default_dialer,
    "Waiting to become the default dialer" to R.string.ui_waiting_to_become_the_default_dialer,
    "Default dialer selection is unavailable on this device." to R.string.ui_default_dialer_selection_is_unavailable_on_this_device,
    "Direct telephony" to R.string.ui_direct_telephony,
    "Enter a port number from 1 to 65535." to R.string.ui_enter_a_port_number_from_1_to_65535,
    "off" to R.string.ui_off,
    "optional" to R.string.ui_optional,
    "mandatory" to R.string.ui_mandatory,
    "Checking gateway setup" to R.string.status_checking_gateway_setup,
    "Enabling gateway after current calls end" to R.string.status_enabling_gateway_after_current_calls_end,
    "Turning gateway off after current calls end" to R.string.status_turning_gateway_off_after_current_calls_end,
    "Waiting to restore the previous dialer" to R.string.status_waiting_to_restore_the_previous_dialer,
    "Gateway off; this app still holds the dialer role" to R.string.status_gateway_off_this_app_still_holds_the_dialer_role,
    "Gateway off" to R.string.status_gateway_off,
    "Gateway service not running" to R.string.status_gateway_service_not_running,
    "Shizuku not running. After starting it, open this app to reconnect" to R.string.status_shizuku_not_running_after_starting_it_open_this_app_to_reconnect,
    "Shizuku not connected" to R.string.status_shizuku_not_connected,
    "Gateway ready" to R.string.status_gateway_ready,
    "Default dialer selection is unavailable on this device" to R.string.status_default_dialer_selection_is_unavailable_on_this_device,
    "Select this app as default dialer to enable gateway calls" to R.string.status_select_this_app_as_default_dialer_to_enable_gateway_calls,
    "Default dialer activity or service is unavailable" to R.string.status_default_dialer_activity_or_service_is_unavailable,
    "Companion access unavailable on Android 11; select default-dialer mode" to R.string.status_companion_access_unavailable_on_android_11_select_default_dialer_,
    "Companion call service is disabled" to R.string.status_companion_call_service_is_disabled,
    "Companion call setup incomplete" to R.string.status_companion_call_setup_incomplete,
    "Calling permissions are missing; re-run setup" to R.string.status_calling_permissions_are_missing_re_run_setup,
    "No cellular call" to R.string.status_no_cellular_call,
    "Cellular call redirection" to R.string.status_cellular_call_redirection,
    "Unavailable" to R.string.status_unavailable,
    "not configured" to R.string.status_not_configured,
    "stopped" to R.string.status_stopped,
    "registering" to R.string.status_registering,
    "starting" to R.string.status_starting,
    "takeover off" to R.string.status_takeover_off,
    "Takeover off. Still your default phone app; change it in Settings." to R.string.status_takeover_off_still_your_default_phone_app_change_it_in_settings,
    "Call takeover off" to R.string.status_call_takeover_off,
    "Loopback test mode" to R.string.status_loopback_test_mode,
    "Shizuku is not running. Start Shizuku, then tap here to reconnect." to R.string.status_shizuku_is_not_running_start_shizuku_then_tap_here_to_reconnect,
    "start Shizuku first" to R.string.status_start_shizuku_first,
    "grant access in Shizuku" to R.string.status_grant_access_in_shizuku,
    "denied in Shizuku" to R.string.status_denied_in_shizuku,
    "Shizuku v13+ required" to R.string.status_shizuku_v13_required,
    "binding UserService" to R.string.status_binding_userservice,
    "This app is now the default dialer." to R.string.status_this_app_is_now_the_default_dialer,
    "Android did not select this app as default dialer. Use system default-app settings." to R.string.status_android_did_not_select_this_app_as_default_dialer_use_system_defa,
    "Default dialer selection was cancelled or declined." to R.string.status_default_dialer_selection_was_cancelled_or_declined,
    "Could not open default dialer selection. Use system default-app settings." to R.string.status_could_not_open_default_dialer_selection_use_system_default_app_se,
    "Open system Settings, then Default apps, and select your usual phone app." to R.string.status_open_system_settings_then_default_apps_and_select_your_usual_phon,
    "Unlock the phone to continue." to R.string.status_unlock_the_phone_to_continue,
    "Waiting for Shizuku. You can cancel the dialer change." to R.string.status_waiting_for_shizuku_you_can_cancel_the_dialer_change,
    "Waiting for current calls to end. You can cancel the dialer change." to R.string.status_waiting_for_current_calls_to_end_you_can_cancel_the_dialer_change,
    "Dialer change cancelled." to R.string.status_dialer_change_cancelled,
    "Could not read the previous dialer. Use system default-app settings." to R.string.status_could_not_read_the_previous_dialer_use_system_default_app_setting,
    "Could not save the previous dialer; no role change was attempted." to R.string.status_could_not_save_the_previous_dialer_no_role_change_was_attempted,
    "Already the default dialer." to R.string.status_already_the_default_dialer,
    "Default dialer has already changed; leaving that choice in place." to R.string.status_default_dialer_has_already_changed_leaving_that_choice_in_place,
    "Default dialer changed while waiting; leaving the new choice in place." to R.string.status_default_dialer_changed_while_waiting_leaving_the_new_choice_in_pl,
    "Waiting for current calls to end." to R.string.status_waiting_for_current_calls_to_end,
    "Previous dialer restored." to R.string.status_previous_dialer_restored,
    "System dialer selected; the previous dialer was unavailable." to R.string.status_system_dialer_selected_the_previous_dialer_was_unavailable,
    "Default dialer changed during restoration. Open system default-app settings to check the selection." to R.string.status_default_dialer_changed_during_restoration_open_system_default_app,
    "Default dialer restored to the system fallback." to R.string.status_default_dialer_restored_to_the_system_fallback,
    "Default dialer changed; leaving the new choice in place." to R.string.status_default_dialer_changed_leaving_the_new_choice_in_place,
    "The Shizuku helper cannot manage default-app roles. Use system selection." to R.string.status_the_shizuku_helper_cannot_manage_default_app_roles_use_system_sel,
    "Could not verify that Telecom is idle. Try again or use system selection." to R.string.status_could_not_verify_that_telecom_is_idle_try_again_or_use_system_sel,
    "STOPPED" to R.string.status_stopped_2,
    "SHIZUKU_NOT_RUNNING" to R.string.status_shizuku_not_running,
    "PERMISSION_REQUIRED" to R.string.status_permission_required,
    "PERMISSION_DENIED" to R.string.status_permission_denied,
    "BINDING" to R.string.status_binding,
    "CONNECTED" to R.string.status_connected,
    "ERROR" to R.string.status_error,
    "shizuku_not_running" to R.string.status_shizuku_not_running_2,
    "permission_required" to R.string.status_permission_required_2,
    "permission_denied" to R.string.status_permission_denied_2,
    "binding" to R.string.status_binding_2,
    "connected" to R.string.status_connected_2,
    "error" to R.string.status_error_2,
)
private val statusPrefixes = listOf(
    StatusPrefix("Selected call audio unavailable: ", R.string.status_selected_call_audio_unavailable_1_s, false),
    StatusPrefix("SIP unavailable: ", R.string.status_sip_unavailable_1_s, true),
    StatusPrefix("held unregistered: ", R.string.status_held_unregistered_1_s, true),
    StatusPrefix("SIP: ", R.string.status_sip_1_s, true),
    StatusPrefix("Ready \u00b7 ", R.string.status_ready_1_s, true),
    StatusPrefix("In call \u00b7 ", R.string.status_in_call_1_s, false),
    StatusPrefix("Setup needed \u00b7 Shizuku ", R.string.status_setup_needed_shizuku_1_s, true),
    StatusPrefix("registration failed: ", R.string.status_registration_failed_1_s, false),
    StatusPrefix("register failed: ", R.string.status_register_failed_1_s, false),
    StatusPrefix("setup failed: ", R.string.status_setup_failed_1_s, false),
    StatusPrefix("start failed: ", R.string.status_start_failed_1_s, false),
    StatusPrefix("SIP worker failed: ", R.string.status_sip_worker_failed_1_s, false),
    StatusPrefix("permission request failed: ", R.string.status_permission_request_failed_1_s, false),
    StatusPrefix("Shizuku check failed: ", R.string.status_shizuku_check_failed_1_s, false),
    StatusPrefix("bindUserService failed: ", R.string.status_binduserservice_failed_1_s, false),
    StatusPrefix("Default dialer did not become this app. ", R.string.status_default_dialer_did_not_become_this_app_1_s, false),
    StatusPrefix("Could not verify dialer restoration; no further role changes were attempted. ", R.string.status_could_not_verify_dialer_restoration_no_further_role_changes_were_, false),
    StatusPrefix("Could not restore a dialer. Open system default-app settings. ", R.string.status_could_not_restore_a_dialer_open_system_default_app_settings_1_s, false),
)
