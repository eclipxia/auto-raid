#!/usr/bin/env python3
"""
Build a void test world containing a litematic (e.g. a raid farm) and a creative
player holding a lot of Ominous Bottles. Stdlib only.

usage: make_void_world.py <schematic.litematic> <saves dir> <1.21.4|1.21.11> [world name]
"""
import io, os, struct, sys, time, zlib, math, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tnbt import *

DATA_VERSION = {"1.21.4": 4189, "1.21.11": 4671}
ORIGIN_Y = 64          # schematic min corner is placed at y=64, x/z centred on 0,0


def load_schematic(path):
    root = load(path)
    region = next(iter(root["Regions"].values()))
    sx, sy, sz = (abs(region["Size"][k]) for k in "xyz")
    pal = region["BlockStatePalette"]
    bits = max(2, math.ceil(math.log2(len(pal))))
    # litematica packs entries continuously (they may span two longs)
    big = int.from_bytes(b"".join(struct.pack("<q", v) for v in region["BlockStates"]), "little")
    mask = (1 << bits) - 1
    blocks = {}
    for i in range(sx * sy * sz):
        idx = (big >> (i * bits)) & mask
        if pal[idx]["Name"] == "minecraft:air":
            continue
        x = i % sx
        z = (i // sx) % sz
        y = i // (sx * sz)
        blocks[(x, y, z)] = pal[idx]
    return (sx, sy, sz), blocks, region["TileEntities"]


def pack_section(states):
    """states: 4096 palette entries (dict-hashable keys) -> block_states compound"""
    palette, index = [], {}
    ids = []
    for st in states:
        key = (st["Name"], tuple(sorted(st.get("Properties", {}).items())))
        if key not in index:
            index[key] = len(palette)
            e = {"Name": st["Name"]}
            if st.get("Properties"):
                e["Properties"] = {k: str(v) for k, v in st["Properties"].items()}
            palette.append(e)
        ids.append(index[key])
    out = {"palette": TList(10, palette)}
    if len(palette) > 1:
        bits = max(4, math.ceil(math.log2(len(palette))))
        per = 64 // bits                     # since 1.16 entries never span longs
        longs = []
        for s in range(0, 4096, per):
            v = 0
            for j, idx in enumerate(ids[s:s + per]):
                v |= idx << (j * bits)
            longs.append(Long(v - (1 << 64) if v >= 1 << 63 else v))
        out["data"] = LArr(longs)
    return out


def write_regions(saves_world, dv, size, blocks, tiles):
    sx, sy, sz = size
    ox, oy, oz = -(sx // 2), ORIGIN_Y, -(sz // 2)
    AIR = {"Name": "minecraft:air"}
    chunks = {}                                   # (cx,cz) -> {(secY): [4096]}
    for (x, y, z), st in blocks.items():
        wx, wy, wz = x + ox, y + oy, z + oz
        sec = chunks.setdefault((wx >> 4, wz >> 4), {}).setdefault(wy >> 4, [AIR] * 4096)
        sec[((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15)] = st
    tiles_by_chunk = {}
    for t in tiles:
        t = dict(t)
        t["x"], t["y"], t["z"] = Int(t["x"] + ox), Int(t["y"] + oy), Int(t["z"] + oz)
        tiles_by_chunk.setdefault((t["x"] >> 4, t["z"] >> 4), []).append(t)

    regions = {}
    for (cx, cz), secs in chunks.items():
        sections = [{"Y": Byte(sy_), "block_states": pack_section(st),
                     "biomes": {"palette": TList(8, ["minecraft:the_void"])}}
                    for sy_, st in sorted(secs.items())]
        chunk = {"DataVersion": Int(dv), "xPos": Int(cx), "zPos": Int(cz), "yPos": Int(-4),
                 "Status": "minecraft:full", "LastUpdate": Long(0), "InhabitedTime": Long(0),
                 "isLightOn": Byte(0), "sections": TList(10, sections),
                 "block_entities": TList(10, tiles_by_chunk.get((cx, cz), [])),
                 "structures": {"References": {}, "starts": {}}}
        raw = write(chunk)
        regions.setdefault((cx >> 5, cz >> 5), {})[(cx & 31, cz & 31)] = zlib.compress(raw)

    os.makedirs(os.path.join(saves_world, "region"), exist_ok=True)
    for (rx, rz), cs in regions.items():
        header, body, sector = bytearray(4096), bytearray(), 2
        stamps = bytearray(4096)
        for (lx, lz), data in cs.items():
            rec = struct.pack(">IB", len(data) + 1, 2) + data
            rec += b"\0" * (-len(rec) % 4096)
            n = len(rec) // 4096
            o = 4 * (lx + 32 * lz)
            header[o:o + 4] = struct.pack(">I", (sector << 8) | n)
            stamps[o:o + 4] = struct.pack(">I", int(time.time()))
            body += rec
            sector += n
        with open(os.path.join(saves_world, "region", f"r.{rx}.{rz}.mca"), "wb") as f:
            f.write(bytes(header) + bytes(stamps) + bytes(body))
    return oy + sy


def level_dat(version, name, spawn_y):
    dv = DATA_VERSION[version]
    bottle = {"id": "minecraft:ominous_bottle", "count": Int(64),
              "components": {"minecraft:ominous_bottle_amplifier": Int(4)}}
    # item component format for enchantments changed in 1.21.5 (the "levels" wrapper was removed)
    ench = ({"minecraft:sharpness": Int(255)} if version == "1.21.11"
            else {"levels": {"minecraft:sharpness": Int(255)}})
    # the enchantment level codec caps at 255 (32767 is rejected), so that is the maximum
    sword = {"id": "minecraft:netherite_sword", "count": Int(1),
             "components": {"minecraft:enchantments": ench, "minecraft:unbreakable": {}}}
    inv = [{"Slot": Byte(0), **sword}]
    for slot in range(1, 10):
        inv.append({"Slot": Byte(slot), **bottle})
    void = {"type": "minecraft:flat", "settings": {
        "biome": "minecraft:the_void", "features": Byte(0), "lakes": Byte(0),
        "layers": TList(10, []), "structure_overrides": TList(8, [])}}
    player = {"DataVersion": Int(dv), "Dimension": "minecraft:overworld",
              "Pos": TList(6, [Double(0.5), Double(spawn_y), Double(0.5)]),
              "Rotation": TList(5, [Float(0), Float(0)]),
              "Motion": TList(6, [Double(0)] * 3),
              "playerGameType": Int(1), "Health": Float(20), "foodLevel": Int(20),
              "abilities": {"invulnerable": Byte(1), "mayfly": Byte(1), "instabuild": Byte(1),
                            "mayBuild": Byte(1), "flying": Byte(1),
                            "walkSpeed": Float(0.1), "flySpeed": Float(0.05)},
              "Inventory": TList(10, inv), "SelectedItemSlot": Int(0)}
    data = {"DataVersion": Int(dv), "version": Int(19133), "LevelName": name,
            "Version": {"Id": Int(dv), "Name": version, "Series": "main", "Snapshot": Byte(0)},
            "GameType": Int(1), "allowCommands": Byte(1), "Difficulty": Byte(2),
            "hardcore": Byte(0), "initialized": Byte(1),
            "DifficultyLocked": Byte(0), "WasModded": Byte(1), "clearWeatherTime": Int(0),
            "rainTime": Int(100000), "thunderTime": Int(100000),
            "WanderingTraderSpawnChance": Int(25), "WanderingTraderSpawnDelay": Int(24000),
            "CustomBossEvents": {}, "ScheduledEvents": TList(10, []),
            "DragonFight": {"NeedsStateScanning": Byte(1), "DragonKilled": Byte(0), "PreviouslyKilled": Byte(0),
                            "Gateways": IArr(range(20))},
            "DataPacks": {"Enabled": TList(8, ["vanilla"]), "Disabled": TList(8, [])},
            "Time": Long(0), "DayTime": Long(6000), "raining": Byte(0), "thundering": Byte(0),
            "LastPlayed": Long(int(time.time() * 1000)),
            "WorldGenSettings": {"seed": Long(0), "generate_features": Byte(0), "bonus_chest": Byte(0),
                                 "dimensions": {"minecraft:overworld": {"type": "minecraft:overworld", "generator": void}}},
            "Player": player}
    if version == "1.21.11":     # spawn became a compound in 1.21.9
        data["spawn"] = {"pos": IArr([0, int(spawn_y), 0]), "pitch": Float(0), "yaw": Float(0),
                         "dimension": "minecraft:overworld"}
    else:
        data.update({"SpawnX": Int(0), "SpawnY": Int(int(spawn_y)), "SpawnZ": Int(0), "SpawnAngle": Float(0)})
    return {"Data": data}


def main():
    schem, saves, version = sys.argv[1:4]
    name = sys.argv[4] if len(sys.argv) > 4 else "Void Raid"
    world = os.path.join(saves, name)
    if os.path.exists(world):
        shutil.rmtree(world)
    os.makedirs(world)
    size, blocks, tiles = load_schematic(schem)
    top = write_regions(world, DATA_VERSION[version], size, blocks, tiles)

    with gzip.open(os.path.join(world, "level.dat"), "wb") as f:
        f.write(write(level_dat(version, name, top + 2)))
    print(f"{version}: wrote {world} ({len(blocks)} blocks, {len(tiles)} block entities)")


if __name__ == "__main__":
    main()
