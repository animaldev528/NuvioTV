package com.nuvio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null,
    // Taste-picker flags. Server-owned: curated_enabled is set by the operator
    // (opt-in per profile); taste_completed flips to true only when the profile
    // runs the picker (sync_push_taste_picks on the backend). Pulled with the
    // profile from sync_pull_profiles and persisted locally like every other field.
    val curatedEnabled: Boolean = false,
    val tasteCompleted: Boolean = false
) {
    val isPrimary: Boolean get() = id == 1

    /** The on-TV taste picker is offered only to operator-curated profiles that
     *  have not yet run it — so a legacy or brand-new profile can never see it. */
    val needsTastePicker: Boolean get() = curatedEnabled && !tasteCompleted
}
