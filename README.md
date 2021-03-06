# ereg_sf

A microservice sending all 'enhetsregister' events (changes from Brønnøysund) from kafka compaction log 'public-ereg-cache-org-json' 
to SalesForce. See companion microservice ereg-splitter-change.

Each event is describe by the following protobuf 3 specification
```proto
// this message will be the key part of kafka payload
message EregOrganisationEventKey {

  string org_number = 1;

  enum OrgType {
    ENHET = 0;
    UNDERENHET = 1;
  }
  OrgType org_type = 2;
}

// this message will be the value part of kafka payload
message EregOrganisationEventValue {

  string org_as_json = 1;
  int32 json_hash_code = 2;
}
```

## Tools
- Kotlin
- Gradle
- Kotlintest test framework

## Components
- Kafka client
- Http4K
- Protobuf 3

## Build
```
./gradlew clean build installDist
```

## Contact us
[Torstein Nesby](mailto://torstein.nesby@nav.no)

For internal resources, send request/questions to slack #crm-plattform-team 