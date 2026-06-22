package dev.codelocator.studio.client

class InMemoryLocatorTransport : LocatorTransport {
    override fun hitTest(x: Int, y: Int): String {
        return """
            {"x":$x,"y":$y,"candidates":[
              {"id":1,"bounds":[0,0,300,160],"zIndex":0.0,"sourceId":5836781683827732682,"semanticsTag":"profile_screen","text":null,"role":null,"composableName":"ProfileScreen","flags":3,"windowId":1,"windowTitle":"MainActivity","windowLayer":1},
              {"id":2,"bounds":[16,90,180,140],"zIndex":1.0,"sourceId":335681370023539180,"semanticsTag":"follow_button","text":"Follow","role":"button","composableName":"Button","flags":1,"windowId":1,"windowTitle":"MainActivity","windowLayer":1}
            ]}
        """.trimIndent()
    }
}
