package cn.rbc.codeeditor.util;

import android.view.KeyCharacterMap;

/** Hardware-key character variants retained from the original editor. */
public final class CharacterVariants {
    private final static java.util.Map<Integer, String> PICKER_SETS = new java.util.HashMap<>();

    static {
        java.util.Map<Integer, String> psets = PICKER_SETS;
        psets.put((int)'A', "\u00C0\u00C1\u00C2\u00C4\u00C6\u00C3\u00C5\u0104\u0100");
        psets.put((int)'C', "\u00C7\u0106\u010C");
        psets.put((int)'D', "\u010E");
        psets.put((int)'E', "\u00C8\u00C9\u00CA\u00CB\u0118\u011A\u0112");
        psets.put((int)'G', "\u011E");
        psets.put((int)'L', "\u0141");
        psets.put((int)'I', "\u00CC\u00CD\u00CE\u00CF\u012A\u0130");
        psets.put((int)'N', "\u00D1\u0143\u0147");
        psets.put((int)'O', "\u00D8\u0152\u00D5\u00D2\u00D3\u00D4\u00D6\u014C");
        psets.put((int)'R', "\u0158");
        psets.put((int)'S', "\u015A\u0160\u015E");
        psets.put((int)'T', "\u0164");
        psets.put((int)'U', "\u00D9\u00DA\u00DB\u00DC\u016E\u016A");
        psets.put((int)'Y', "\u00DD\u0178");
        psets.put((int)'Z', "\u0179\u017B\u017D");
        psets.put((int)'a', "\u00E0\u00E1\u00E2\u00E4\u00E6\u00E3\u00E5\u0105\u0101");
        psets.put((int)'c', "\u00E7\u0107\u010D");
        psets.put((int)'d', "\u010F");
        psets.put((int)'e', "\u00E8\u00E9\u00EA\u00EB\u0119\u011B\u0113");
        psets.put((int)'g', "\u011F");
        psets.put((int)'i', "\u00EC\u00ED\u00EE\u00EF\u012B\u0131");
        psets.put((int)'l', "\u0142");
        psets.put((int)'n', "\u00F1\u0144\u0148");
        psets.put((int)'o', "\u00F8\u0153\u00F5\u00F2\u00F3\u00F4\u00F6\u014D");
        psets.put((int)'r', "\u0159");
        psets.put((int)'s', "\u00A7\u00DF\u015B\u0161\u015F");
        psets.put((int)'t', "\u0165");
        psets.put((int)'u', "\u00F9\u00FA\u00FB\u00FC\u016F\u016B");
        psets.put((int)'y', "\u00FD\u00FF");
        psets.put((int)'z', "\u017A\u017C\u017E");
        psets.put((int)KeyCharacterMap.PICKER_DIALOG_INPUT,
                  "\u2026\u00A5\u2022\u00AE\u00A9\u00B1[]{}\\|");
        psets.put((int)'/', "\\");

        // From packages/inputmethods/LatinIME/res/xml/kbd_symbols.xml

        psets.put((int)'1', "\u00b9\u00bd\u2153\u00bc\u215b");
        psets.put((int)'2', "\u00b2\u2154");
        psets.put((int)'3', "\u00b3\u00be\u215c");
        psets.put((int)'4', "\u2074");
        psets.put((int)'5', "\u215d");
        psets.put((int)'7', "\u215e");
        psets.put((int)'0', "\u207f\u2205");
        psets.put((int)'$', "\u00a2\u00a3\u20ac\u00a5\u20a3\u20a4\u20b1");
        psets.put((int)'%', "\u2030");
        psets.put((int)'*', "\u2020\u2021");
        psets.put((int)'-', "\u2013\u2014");
        psets.put((int)'+', "\u00b1");
        psets.put((int)'(', "[{<");
        psets.put((int)')', "]}>");
        psets.put((int)'!', "\u00a1");
        psets.put((int)'"', "\u201c\u201d\u00ab\u00bb\u02dd");
        psets.put((int)'?', "\u00bf");
        psets.put((int)',', "\u201a\u201e");

        // From packages/inputmethods/LatinIME/res/xml/kbd_symbols_shift.xml

        psets.put((int)'=', "\u2260\u2248\u221e");
        psets.put((int)'<', "\u2264\u00ab\u2039");
        psets.put((int)'>', "\u2265\u00bb\u203a");
    }
    public static String get(int key) { return PICKER_SETS.get(key); }
    private CharacterVariants() {}
}
