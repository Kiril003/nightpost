package local.phantom.companion.core.tool

/**
 * Enumeration of all permissions a [Tool] may require. A tool declares
 * the set it needs in [Tool.permissions]; the agentic loop checks
 * [ToolContext.approvedPermissions] before dispatch. Unapproved permissions
 * result in [ToolResult.PermissionDenied] — never a silent skip.
 */
enum class ToolPermission {
    SHELL_EXEC,
    NETWORK,
    FILE_READ,
    FILE_WRITE,
    SETTINGS_WRITE,
    ACCESSIBILITY,
    MEDIA_CONTROL,
    NOTIFICATION_POST,
    LAUNCHER,
    CAMERA,
    MICROPHONE,
    LOCATION,
    CONTACTS_READ,
    CALENDAR_READ_WRITE,
}
