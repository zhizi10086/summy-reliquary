package com.summy.reliquary.sin;

/**
 * 七宗罪。
 *
 * <p>枚举顺序 = 显示顺序（与七德一一配对：傲慢-谦逊、贪婪-慷慨、色欲-贞洁、嫉妒-善良、暴食-节制、愤怒-耐心、懒惰-勤勉）。
 */
public enum Sin {
	PRIDE("pride", 0xA020F0),
	GREED("greed", 0xFFD700),
	LUST("lust", 0xE0115F),
	ENVY("envy", 0x0A8A5A),
	GLUTTONY("gluttony", 0xFF8C00),
	WRATH("wrath", 0xFF4500),
	SLOTH("sloth", 0xB0B0B0);

	private final String id;
	/** 该罪的「对应色」：与七宗罪碎片的名字颜色共用同一套 */
	private final int color;

	Sin(String id, int color) {
		this.id = id;
		this.color = color;
	}

	/** 命令与存档里使用的 id */
	public String id() {
		return id;
	}

	/** 该罪的对应色（碎片名字色 / 觉醒台词色都取这里） */
	public int color() {
		return color;
	}

	/** 觉醒台词的语言键（每个罪一句） */
	public String awakenedKey() {
		return "message.summy-reliquary.sin.awakened." + id;
	}

	/** 提示文本语言键 */
	public String nameKey() {
		return "item.summy-reliquary.sin." + id;
	}

	/** 按 id 找罪（命令用），找不到返回 null */
	public static Sin byId(String id) {
		for (Sin sin : values()) {
			if (sin.id.equals(id)) {
				return sin;
			}
		}
		return null;
	}
}
