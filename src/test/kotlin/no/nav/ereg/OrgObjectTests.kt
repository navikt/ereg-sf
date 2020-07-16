package no.nav.ereg

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.ereg.proto.EregOrganisationEventKey
import no.nav.ereg.proto.EregOrganisationEventValue

class OrgObjectTests : StringSpec() {

    private val protoOOK = EregOrganisationEventKey.newBuilder().apply {
        orgNumber = "1234"
        orgType = EregOrganisationEventKey.OrgType.ENHET
    }.build().toByteArray()
    private val protoOOV = EregOrganisationEventValue.newBuilder().apply {
        orgAsJson = "ORG_AS_JSON"
        jsonHashCode = 1
    }.build().toByteArray()

    init {
        "fun fromProto should work as expected" {

            OrgObjectBase.fromProto(protoOOK, protoOOV).shouldBeInstanceOf<OrgObject>()

            OrgObjectBase.fromProto(
                "invalid".toByteArray(),
                "invalid".toByteArray()
            ).shouldBeInstanceOf<OrgObjectProtobufIssue>()

            OrgObjectBase.fromProto(protoOOK, "invalid".toByteArray()).shouldBeInstanceOf<OrgObjectProtobufIssue>()
            OrgObjectBase.fromProto("invalid".toByteArray(), protoOOV).shouldBeInstanceOf<OrgObjectProtobufIssue>()
        }
    }
}
