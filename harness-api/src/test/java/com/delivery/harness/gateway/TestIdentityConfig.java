package com.delivery.harness.gateway;

/** Hashes of public synthetic test credentials; no static credential is ever accepted by pilot. */
public final class TestIdentityConfig {
    private TestIdentityConfig() {}

    public static final String AGENT =
            "{\"token_sha256\":"
                + "\"b83e3e191f2acfe2c3c27f2079c5c13ea8409e9abbf31b671d421d80bbc847c9\","
                + "\"tenant_id\":\"demo\",\"user_id\":\"demo-agent\",\"roles\":[\"AGENT\",\"OPERATOR\"],"
                + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}";

    public static final String QUALITY =
            "["
                    + AGENT
                    + ",{\"token_sha256\":\"e90a8146064ee89f60742796d670a904e884ebea90bcf915b0cad0fbfabbd05a\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"quality-author\",\"roles\":[\"QUALITY\",\"POLICY_EDITOR\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}"
                    + ",{\"token_sha256\":\"8de6facf79150649f50bb0e3e3b6a8deef12a70f2497d8f4619896857233c919\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"publisher\",\"roles\":[\"QUALITY\",\"POLICY_PUBLISHER\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                    + "{\"token_sha256\":\"1f055d775136f0859225d081f921e3c8f8f7c653b847a964dc23b69bef8bb866\","
                    + "\"tenant_id\":\"other\",\"user_id\":\"other\",\"roles\":[\"QUALITY\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                    + "{\"token_sha256\":\"05899c2610380d4fa485faafedf5349dfb8dbeb0de82824031d545c2f55ae70d\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"scoped\",\"roles\":[\"POLICY_EDITOR\"],"
                    + "\"city_codes\":[\"010\"],\"station_ids\":[\"S001\"]}]";

    public static final String PLATFORM =
            "["
                    + AGENT
                    + ",{\"token_sha256\":\"baf096fd9de6850705ebb06f5f4f012a28876ba2817d7d54688d5e5124ceb78b\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"independent-reviewer\","
                    + "\"roles\":[\"REVIEWER\",\"SUPERVISOR\",\"AUDITOR\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                    + "{\"token_sha256\":\"ceb23d9acfebf52d9a5c12e42b8f5427df774fc9cadc93d001d9a743f1cb7a8f\","
                    + "\"tenant_id\":\"other-tenant\",\"user_id\":\"other\",\"roles\":[\"AGENT\",\"AUDITOR\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}]";
}
