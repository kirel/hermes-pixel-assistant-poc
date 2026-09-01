package de.danielkirs.hermesassistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            text = "Hermes Assistant POC\n\nDieser Proof of Concept antwortet bei jeder Assistant-Aktivierung ausschließlich mit: OK"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        layout.addView(Button(this).apply {
            text = "Als Standard-Assistent auswählen"
            setOnClickListener { requestAssistantRole() }
        })
        setContentView(layout)
    }

    private fun requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 100)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }
}
