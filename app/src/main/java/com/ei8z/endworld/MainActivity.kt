package com.ei8z.endworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ei8z.endworld.ui.screen.GameScreen
import com.ei8z.endworld.ui.screen.InitializationScreen
import com.ei8z.endworld.ui.viewmodel.GameViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: GameViewModel = viewModel(factory = GameViewModel.Factory)
                    val playerState by viewModel.playerState.collectAsState(initial = null)

                    if (playerState == null) {
                        InitializationScreen(
                            onStartGame = { name, gender, personality, doomType ->
                                viewModel.initializeGame(name, gender, personality, doomType)
                            }
                        )
                    } else {
                        GameScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
