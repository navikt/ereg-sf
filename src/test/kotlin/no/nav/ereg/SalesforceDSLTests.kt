package no.nav.ereg

import io.kotlintest.TestCase
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import no.nav.ereg.proto.EregOrganisationEventKey
import no.nav.ereg.proto.EregOrganisationEventValue

class SalesforceDSLTests : StringSpec() {

    init {

        "SalesforceDSL should not invoke doSomething when failed access request" {

            sfAPI(9042, SFScenarios.FailedAuth) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) {
                    true shouldBe false // should never be invoked
                } shouldBe false
            }
        }

        "SalesforceDSL should not invoke doSomething when invalid access response" {

            sfAPI(9042, SFScenarios.OkAuthInvalidToken) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) {
                    true shouldBe false // should never be invoked
                } shouldBe false
            }
        }

        "SalesforceDSL should invoke doSomething when successful access request" {

            sfAPI(9042, SFScenarios.OkAuthValidToken) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) {
                    true shouldBe true
                } shouldBe true
            }
        }

        "SalesforceDSL should refresh access token once if expired" {

            val nPosts = 3

            sfAPI(9042, SFScenarios.OkAuthValidTokenExpired) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) { doPost ->

                    (1..nPosts).forEach { _ ->
                        doPost(
                            listOf(
                                OrgObject(
                                    EregOrganisationEventKey.getDefaultInstance(),
                                    EregOrganisationEventValue.getDefaultInstance()
                                )
                            )
                        ) shouldBe true
                    }
                } shouldBe true
            }

            Metrics.failedRequest.get().toInt() shouldBe 1
            Metrics.successfulRequest.get().toInt() shouldBe nPosts
        }

        "SalesforceDSL should return true for doPost for all successful posts" {

            val nPosts = 3

            sfAPI(9042, SFScenarios.OkAuthValidTokenNotExpired) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) { doPost ->

                    (1..nPosts).forEach { _ ->
                        doPost(
                            listOf(
                                OrgObject(
                                    EregOrganisationEventKey.getDefaultInstance(),
                                    EregOrganisationEventValue.getDefaultInstance()
                                )
                            )
                        ) shouldBe true
                    }
                } shouldBe true
            }

            Metrics.failedRequest.get().toInt() shouldBe 0
            Metrics.successfulRequest.get().toInt() shouldBe nPosts
        }

        "SalesforceDSL should return false for doPost for all failed posts" {

            val nPosts = 3

            sfAPI(9042, SFScenarios.FailedPost) { authURL, rURL ->

                getSalesforcePost(EnvVar(sfOAuthUrl = authURL, sfRestEndpoint = rURL)) { doPost ->

                    (1..nPosts).forEach { _ ->
                        doPost(
                            listOf(
                                OrgObject(
                                    EregOrganisationEventKey.getDefaultInstance(),
                                    EregOrganisationEventValue.getDefaultInstance()
                                )
                            )
                        ) shouldBe false
                    }
                } shouldBe true
            }

            Metrics.failedRequest.get().toInt() shouldBe nPosts
            Metrics.successfulRequest.get().toInt() shouldBe 0
        }
    }

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)
        ServerState.reset()
        ShutdownHook.reset()
        Metrics.resetAll()
    }
}
