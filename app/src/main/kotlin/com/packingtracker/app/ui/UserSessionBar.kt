package com.packingtracker.app.ui

import android.view.View
import android.widget.TextView
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.databinding.IncludeUserSessionBinding

object UserSessionBar {
    fun bind(binding: IncludeUserSessionBinding) {
        bind(binding.userSessionBar, binding.toolbarUserName)
    }

    fun bind(container: View, nameView: TextView) {
        val session = UserSessionStore(container.context).load()
        val label = when {
            !session.isValid() -> null
            !session.usuarioNombre.isNullOrBlank() -> session.usuarioNombre
            !session.usuarioCodigo.isNullOrBlank() -> session.usuarioCodigo
            else -> null
        }
        if (label.isNullOrBlank()) {
            container.visibility = View.GONE
        } else {
            container.visibility = View.VISIBLE
            nameView.text = label
        }
    }
}
