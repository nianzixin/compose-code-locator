package dev.codelocator.studio.navigation

class SourceNavigator : Navigator {
    override fun open(request: NavigationRequest) {
        error("Use IdeNavigator inside the IntelliJ plugin runtime.")
    }
}
