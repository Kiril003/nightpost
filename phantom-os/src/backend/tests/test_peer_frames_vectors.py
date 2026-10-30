"""Читачі кадрів телефона — на рядках із Kotlin-тестів core-net.

PeerReviseTest, PeerReadTest, PeerTypingTest, PeerPayloadTest, PeerCardTest,
PeerLiveTest: той самий рядок має читатись тут рівно так, як там.
"""
from __future__ import annotations

from node import peer_frames as pf
from node import peer_read as rd
from node import peer_revise as rv

KANBAN = "{\"id\":\"kanban_1\",\"title\":\"Вихід\",\"columns\":[{\"id\":\"todo\",\"cards\":[]}]}"


def test_revise_reads_what_it_wrote_and_refuses_machine_words():
    text = "о сьомій; не о шостій\nі з 🇺🇦"
    assert rv.decode(rv.encode(rv.Edit("m-1", text))) == rv.Edit("m-1", text)
    assert rv.decode(rv.encode(rv.Unsend("m-1"))) == rv.Unsend("m-1")
    assert rv.encode(rv.Edit("m-1", "PH-LOC:50.1,30.5")) is None
    assert rv.encode(rv.Edit("m-1", "  ")) is None
    assert rv.encode(rv.Edit("m-1", "а" * rv.MAX_TEXT_BYTES)) is None
    assert rv.encode(rv.Edit("m;1", "так")) is None
    # Base64url без вирівнювання — як java.util.Base64.getUrlEncoder().withoutPadding().
    assert rv.encode(rv.Edit("m-1", "так")) == "PH-EDT:m-1;0YLQsNC6"
    assert rv.decode("PH-EDT:m-1;0YLQsNC6;x") is None
    assert rv.decode("PH-UNS:m;1") is None


def test_read_frame_vectors():
    t0 = 1_788_517_702_898
    assert rd.decode(rd.encode(rd.Read(t0))) == rd.Read(t0)
    assert rd.decode(rd.encode(rd.Read(t0, "a1b2c3d4e5f6"))) == rd.Read(t0, "a1b2c3d4e5f6")
    assert rd.encode(rd.Read(0)) is None
    assert rd.encode(rd.Read(t0, "a;b")) is None
    assert rd.decode("PH-RDU:абв") is None
    assert rd.decode("PH-RDU:1;g;x") is None


def test_place_vectors():
    assert pf.decode_place("PH-LOC:") is None
    assert pf.decode_place("PH-LOC:abc,def") is None
    assert pf.decode_place("PH-LOC:50.45") is None
    assert pf.decode_place("PH-LOC:91.0,30.0") is None
    assert pf.decode_place("PH-LOC:50.0,181.0") is None
    assert pf.decode_place("PH-LOC:-90.1,0.0") is None
    assert pf.decode_place("PH-LOC:50.45010,30.52340") == pf.Place(50.4501, 30.5234, None)
    assert pf.decode_place("PH-LOC:50.45010,30.52340;12") == pf.Place(50.4501, 30.5234, 12)
    assert pf.decode_place("PH-LOC:50.45010,30.52340;12;Кафе на розі").accuracy_m == 12
    assert pf.decode_place("PH-LOC:50.45010,30.52340;12;Кафе; і бар").accuracy_m == 12
    assert pf.decode_place("PH-LOC:50.45010,30.52340;;Кафе").accuracy_m is None
    assert pf.decode_place("PH-LOC:91.0,30.0;12;Кафе") is None
    assert pf.encode_place(50.4501, 30.5234, 12) == "PH-LOC:50.45010,30.52340;12"
    assert pf.encode_place(50.4501, 30.5234) == "PH-LOC:50.45010,30.52340"


def test_card_vectors():
    for mark, web in pf.CARDS.items():
        frame = pf.encode_card(web, KANBAN)
        assert frame is not None and frame.startswith(f"PH-CRD:{mark};")
        assert pf.decode_card(frame) == pf.Card(web, KANBAN)
    assert len(set(pf.CARDS.values())) == 13
    assert pf.decode_card("PH-CRD:zz;aGk") is None
    assert pf.decode_card("PH-CRD:sv;") is None
    assert pf.decode_card("PH-CRD:sv") is None
    assert pf.decode_card("PH-CRD:sv;!!!!") is None
    assert pf.decode_card("PH-RTE:1788;337") is None
    assert pf.encode_card("widget:gantt", KANBAN) is None


def test_route_reads_the_google_polyline():
    shape = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    assert pf.decode_path(shape) == [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]
    route = pf.decode_route(f"PH-RTE:4102444800000;3370;420;foot;_p~iF~ps|U_ulLnnqC;{shape}")
    assert route is not None and route.profile == "foot" and len(route.shape) == 3
    assert pf.decode_route("PH-RTE:1788;337") is None
    assert pf.decode_route(f"PH-RTE:0;3370;420;foot;_p~iF~ps|U_ulLnnqC;{shape}") is None
    # Одна точка — не маршрут.
    assert pf.decode_route(f"PH-RTE:4102444800000;3370;420;foot;_p~iF~ps|U;{shape}") is None


def test_attachment_needs_all_five_fields():
    base = ('{"blob_id":"0123456789abcdef0123456789abcdef","key_hex":"aa","sha256":"cc",'
            '"size":10,"name":"IMG.jpg","mime":"image/jpeg"}')
    att = pf.describe_attachment(base)
    assert att is not None and pf.attachment_kind(att) == "photo"
    assert pf.describe_attachment(base.replace('"key_hex":"aa",', "")) is None
    assert pf.describe_attachment(base.replace('"size":10', '"size":0')) is None
    assert pf.describe_attachment('{"blob_id":"x"}') is None
    assert pf.describe_attachment("просто {текст}") is None


def test_profile_banner_vectors():
    """Ті самі золоті рядки, що PeerBannerTest.kt і profileWireCodec.test.ts."""
    from node import peer_profile as pp

    golden = "PH-PRF:AQQAAAAHCwcBAgGATQD/DCYAESIzRFVmd4iZqrvM3e7/AAPo/+7dzLuqmYh3ZlVEMyIRAABgAA"
    banner = pp.Banner((pp.BannerItem("00112233445566778899aabbccddeeff", 1000, 128, 77),
                        pp.BannerItem("ffeeddccbbaa99887766554433221100", 24576, 0, 255)), video_still=True)
    assert pp.encode_profile(pp.Profile(7, banner=banner)) == golden
    assert pp.decode_profile(golden).banner == banner
    assert pp.encode_profile(pp.Profile(8, banner="")) == "PH-PRF:AQQAAAAICwA"
    assert pp.decode_profile("PH-PRF:AQQAAAAICwA").banner == ""
    future = pp.decode_profile("PH-PRF:AQQAAAAHBgJJYQsQAgcAAAAAAAAAAAAAAAAAAA")
    assert future is not None and future.name == "Ia" and future.banner is None
    assert pp.encode_profile(pp.Profile(1, banner=pp.Banner((pp.BannerItem("ab" * 16, 10),) * 7))) is None
    jpeg = bytes([0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10])
    assert pp.hash_of(jpeg) == "fc16d7dcee9cae83ef3923222a81ccd8"
    assert pp.encode_banner_photo(jpeg) == "PH-PBN:AfwW19zunK6D7zkjIiqBzNj/2P/gABA"
    assert pp.decode_banner_photo("PH-PBN:AfwW19zunK6D7zkjIiqBzNj/2P/gABA") == ("fc16d7dcee9cae83ef3923222a81ccd8", jpeg)
    assert pp.decode_banner_photo("PH-PBN:AfwW19zunK6D7zkjIiqBzNj/2P/gAAA") is None
    assert pp.encode_banner_photo(b"\x01\x02\x03") is None
    assert pf.BANNER in pf.QUIET and pf.is_machine("PH-PBN:x")
