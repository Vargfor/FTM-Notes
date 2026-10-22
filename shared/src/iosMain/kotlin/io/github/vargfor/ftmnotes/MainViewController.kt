package io.github.vargfor.ftmnotes

import androidx.compose.ui.window.ComposeUIViewController
import io.github.vargfor.ftmnotes.di.initKoin
import platform.UIKit.UIViewController

private var koinStarted = false

/** Called from SwiftUI (`ContentView.swift`). */
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        initKoin()
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
