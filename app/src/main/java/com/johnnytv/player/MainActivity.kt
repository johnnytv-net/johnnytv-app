package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Legacy shim.
 *
 * Earlier versions of the app started here. Uploading to GitHub replaces files
 * but never removes them, so this stays as a harmless stand-in that overwrites
 * the old MainActivity rather than leaving code behind that no longer compiles.
 * Nothing launches it - it is not declared in the manifest. Safe to delete once
 * the old copy is gone from your repository.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
