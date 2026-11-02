package com.github.caiheyu.spendy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.spendy.ui.SpendyApp
import com.github.caiheyu.spendy.ui.SpendyViewModel
import com.github.caiheyu.spendy.ui.theme.SpendyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: SpendyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            SpendyTheme(settings) { SpendyApp(model) }
        }
    }
}
