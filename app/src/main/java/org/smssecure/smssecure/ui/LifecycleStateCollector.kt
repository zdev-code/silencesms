package org.smssecure.smssecure.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object LifecycleStateCollector {
  @JvmStatic
  fun <T> collect(owner: LifecycleOwner, state: StateFlow<T>, renderer: Renderer<T>): Job =
    owner.lifecycleScope.launch {
      owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        state.collect(renderer::render)
      }
    }

  fun interface Renderer<T> {
    fun render(state: T)
  }
}