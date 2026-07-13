package org.WenuLink.adapters.mission

sealed interface ItemAssemblyResult {
    data object Accepted : ItemAssemblyResult
    data object UnsupportedCommand : ItemAssemblyResult
    data object UnsupportedFrame : ItemAssemblyResult
    data object NoActiveMission : ItemAssemblyResult
}
