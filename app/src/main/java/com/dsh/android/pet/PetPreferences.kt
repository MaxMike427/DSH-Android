package com.dsh.android.pet

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing switch for the desktop pet.
 *
 * The pet itself is a harness plugin (`dsh-pet`) that draws inside the Web UI.
 * This switch only decides what the *host* does with it: whether to keep a
 * floating copy over other apps while this app is in the background.
 *
 * It defaults to on, which is the behaviour the user asked for — the pet is
 * visible inside the app and floats once the app leaves the screen.
 */
class PetPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Float the pet over other apps while this app is backgrounded. */
    var floatEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOAT, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOAT, value).apply()

    /**
     * Where the user last left the floating pet, in screen pixels, or
     * [UNSET] before it has ever been dragged.
     *
     * Persisting it means the pet comes back where it was put instead of
     * jumping back to the default corner each time the app is backgrounded.
     */
    var petX: Int
        get() = prefs.getInt(KEY_X, UNSET)
        set(value) = prefs.edit().putInt(KEY_X, value).apply()

    var petY: Int
        get() = prefs.getInt(KEY_Y, UNSET)
        set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    companion object {
        private const val FILE = "dsh-pet"
        private const val KEY_FLOAT = "float-enabled"
        private const val KEY_X = "pet-x"
        private const val KEY_Y = "pet-y"

        /** Sentinel for "no position recorded yet". */
        const val UNSET = Int.MIN_VALUE

        /**
         * Read the switch without needing an instance, for callers that only
         * have a [Context] (the overlay service, the lifecycle observer).
         */
        fun of(context: Context): PetPreferences = PetPreferences(context)
    }
}
