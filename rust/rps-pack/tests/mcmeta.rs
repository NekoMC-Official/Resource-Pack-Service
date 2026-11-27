use serde_json::json;

#[test]
fn prefers_min_and_max_format() {
    let meta = json!({"pack":{"min_format":97,"max_format":98}});
    assert_eq!(
        rps_pack::mcmeta::interval(&meta, "probe").unwrap(),
        (97, 98)
    );
}

#[test]
fn falls_back_to_supported_formats() {
    let meta = json!({"pack":{"supported_formats":[97,99]}});
    assert_eq!(
        rps_pack::mcmeta::interval(&meta, "probe").unwrap(),
        (97, 99)
    );
}
