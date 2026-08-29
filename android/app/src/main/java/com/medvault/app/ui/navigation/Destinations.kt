package com.medvault.app.ui.navigation

object Destinations {
    const val UNLOCK = "unlock"
    const val PROFILES = "profiles"
    const val DOCUMENTS = "documents/{profileId}"
    const val IMPORT = "import/{profileId}?mode={mode}&sharedUri={sharedUri}"
    const val DOCUMENT_DETAIL = "document/{documentId}"
    const val SETTINGS = "settings"

    fun documents(profileId: Long) = "documents/$profileId"

    fun import(profileId: Long, mode: String, sharedUri: String? = null): String {
        val base = "import/$profileId?mode=$mode"
        return if (sharedUri != null) "$base&sharedUri=${android.net.Uri.encode(sharedUri)}" else base
    }

    fun documentDetail(documentId: Long) = "document/$documentId"

    const val ARG_PROFILE_ID = "profileId"
    const val ARG_MODE = "mode"
    const val ARG_SHARED_URI = "sharedUri"
    const val ARG_DOCUMENT_ID = "documentId"

    object ImportMode {
        const val FILE = "file"
        const val SCAN = "scan"
        const val SHARED = "shared"
    }
}
