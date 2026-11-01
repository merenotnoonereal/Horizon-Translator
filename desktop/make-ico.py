# Wraps a PNG in a Windows .ico (Windows Vista+ reads PNG data inside .ico files directly).
import struct, sys
png = open(sys.argv[1], "rb").read()
w, h = struct.unpack(">II", png[16:24])
entry = struct.pack("<BBBBHHII", w % 256, h % 256, 0, 0, 1, 32, len(png), 6 + 16)
open(sys.argv[2], "wb").write(struct.pack("<HHH", 0, 1, 1) + entry + png)
