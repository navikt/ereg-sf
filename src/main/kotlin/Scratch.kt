import java.net.URI

fun main() {

    val s = "http://webproxy.nais:8088"
    println(URI(s).host)
    println(URI(s).port)
    println(URI(s).scheme)
}
