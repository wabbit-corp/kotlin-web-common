package one.wabbit.web

interface ExampleApi {
    suspend fun doSomething(): String
}

object ExampleApiRestClient : ExampleApi {
    override suspend fun doSomething(): String {
        return "Hello, World!"
    }
}
