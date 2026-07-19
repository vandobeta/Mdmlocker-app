const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

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

        // If the event is a locked setting breach attempt, we raise administrative warnings
        if (logData.eventType === "KID_MODIFICATION" && logData.message.includes("modified allowed policy")) {
            console.warn(`[MDM WARNING] Device ${deviceId} attempted local policy modification: ${logData.message}`);
            
            // Log security warning under administration logs
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
    // Basic auth check
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
