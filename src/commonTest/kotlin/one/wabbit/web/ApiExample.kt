// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web

interface ExampleApi {
    suspend fun doSomething(): String
}

object ExampleApiRestClient : ExampleApi {
    override suspend fun doSomething(): String = "Hello, World!"
}
