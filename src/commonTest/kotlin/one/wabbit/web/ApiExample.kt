// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web

interface ExampleApi {
    suspend fun doSomething(): String
}

object ExampleApiRestClient : ExampleApi {
    override suspend fun doSomething(): String = "Hello, World!"
}
