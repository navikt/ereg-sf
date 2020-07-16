package no.nav.ereg

import no.nav.ereg.proto.EregOrganisationEventKey
import no.nav.ereg.proto.EregOrganisationEventValue

internal sealed class OrgObjectBase {
    companion object {
        fun fromProto(key: ByteArray, value: ByteArray): OrgObjectBase =
            runCatching {
                OrgObject(EregOrganisationEventKey.parseFrom(key), EregOrganisationEventValue.parseFrom(value))
            }.getOrDefault(OrgObjectProtobufIssue)
    }
}

internal object OrgObjectProtobufIssue : OrgObjectBase()

internal data class OrgObject(
    val key: EregOrganisationEventKey,
    val value: EregOrganisationEventValue
) : OrgObjectBase()
