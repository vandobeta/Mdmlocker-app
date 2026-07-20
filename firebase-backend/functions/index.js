const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

/**
 * Seeds an enterprise-grade default skeleton when a new device registers.
 */
exports.onDeviceRegistered = functions.database
    .ref("/devices/{deviceId}")
    .onCreate(async (snapshot, context) => {
        const { deviceId } = context.params;
        const existing = snapshot.val();
        const updates = {};
        if (!existing.globalSettings) {
            updates["/devices/" + deviceId + "/globalSettings/airplane_mode_on"] = "0";
            updates["/devices/" + deviceId + "/globalSettings/wifi_on"] = "1";
            updates["/devices/" + deviceId + "/globalSettings/mobile_data"] = "1";
            updates["/devices/" + deviceId + "/globalSettings/bluetooth_on"] = "1";
            updates["/devices/" + deviceId + "/globalSettings/adb_enabled"] = "0";
            updates["/devices/" + deviceId + "/globalSettings/development_settings_enabled"] = "0";
            updates["/devices/" + deviceId + "/globalSettings/auto_time"] = "1";
            updates["/devices/" + deviceId + "/globalSettings/auto_time_zone"] = "1";
        }
        if (!existing.secureSettings) {
            updates["/devices/" + deviceId + "/secureSettings/install_non_market_apps"] = "0";
            updates["/devices/" + deviceId + "/secureSettings/location_mode"] = "3";
            updates["/devices/" + deviceId + "/secureSettings/skip_first_use_hints"] = "0";
        }
        if (!existing.systemTable) {
            updates["/devices/" + deviceId + "/systemTable/screen_brightness"] = "128";
            updates["/devices/" + deviceId + "/systemTable/screen_off_timeout"] = "30000";
            updates["/devices/" + deviceId + "/systemTable/haptic_feedback_enabled"] = "1";
            updates["/devices/" + deviceId + "/systemTable/accelerometer_rotation"] = "1";
            updates["/devices/" + deviceId + "/systemTable/time_12_24"] = "24";
        }
        if (Object.keys(updates).length > 0) {
            await admin.database().ref().update(updates);
        }
        return null;
    });

/**
 * Cloud Function to handle automated MDM policy violations.
 * Triggers when a kid or employee attempts to modify a locked settings parameter.
 * It immediately alerts administrators and logs the attempt.
 */
exports.onMdmViolation = functions.database
    .ref("/devices/{deviceId}/auditLogs/{logId}")
    .onCreate(async (snapshot, context) => {
        const logData = snapshot.val();
        const { deviceId } = context.params;

        if (logData.eventType === "KID_MODIFICATION" && logData.message && logData.message.includes("modified allowed policy")) {
            console.warn(`[MDM WARNING] Device ${deviceId} attempted local policy modification: ${logData.message}`);
            const alertRef = admin.database().ref(`/devices/${deviceId}/securityAlerts`).push();
            await alertRef.set({
                timestamp: admin.database.ServerValue.TIMESTAMP,
                type: "UNAUTHORIZED_MODIFICATION_ATTEMPT",
                detail: logData.message,
                severity: "HIGH"
            });
        }
    });

/**
 * Cloud Function to dispatch bulk command queue packets to multiple devices.
 */
exports.dispatchBulkCommand = functions.https.onCall(async (data, context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError(
            "unauthenticated",
            "Bulk actions require administrative credential verification."
        );
    }

    const { deviceIds, commandType, target, value } = data;
    if (!Array.isArray(deviceIds) || !commandType) {
        throw new functions.https.HttpsError(
            "invalid-argument",
            "Missing device target arrays or execution directives."
        );
    }

    const updates = {};
    const timestamp = Date.now();

    deviceIds.forEach((id) => {
        const cmdId = "cmd-" + Math.random().toString(36).substring(2, 10);
        updates[`/devices/${id}/commands/${cmdId}`] = {
            id: cmdId,
            commandType: commandType,
            target: target || null,
            value: value || null,
            timestamp: timestamp,
            executed: false
        };
    });

    try {
        await admin.database().ref().update(updates);
        return { success: true, count: deviceIds.length };
    } catch (e) {
        console.error("Bulk dispatch failure", e);
        throw new functions.https.HttpsError("internal", "Failed to update bulk command records: " + e.message);
    }
});
