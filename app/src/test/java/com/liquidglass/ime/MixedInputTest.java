package com.liquidglass.ime;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** 九宫格左列字母微调：数字与显式字母混合查询。 */
public class MixedInputTest {
    private PinyinEngine engine() throws Exception {
        PinyinEngine e = new PinyinEngine();
        e.load(new ByteArrayInputStream(("nihao\t你好\tni hao\t1000\nnihao\t妮好\tni hao\t10\nshurufa\t输入法\tshu ru fa\t300\n").getBytes(StandardCharsets.UTF_8)));
        return e;
    }

    @Test public void explicitLetterKeepsOnlyMatchingSpellings() throws Exception {
        PinyinEngine e = engine();
        // 64426 中第 2 位固定为 i，剩下的拼写只可能是 n-i-h-a-o
        assertTrue(e.candidates("6i426", true, false).stream().anyMatch(c -> c.text.equals("你好")));
        // 固定为 x 时同键位仍成立，但没有 n-x-h-a-o 这样的词
        assertTrue(e.candidates("6x426", true, false).stream().noneMatch(c -> c.text.equals("你好")));
    }

    @Test public void allDigitQueryIsUnchanged() throws Exception {
        PinyinEngine e = engine();
        PinyinEngine.Candidate c = e.candidates(PinyinEngine.t9("nihao"), true, false).get(0);
        assertEquals("你好", c.text);
        assertEquals("nihao", c.spelling);
    }

    @Test public void mixedKeyMapsLettersBackToDigitsForSearch() throws Exception {
        assertEquals("64426", PinyinEngine.t9Mixed("6i426"));
        assertEquals("64426", PinyinEngine.t9Mixed("nihao"));
        assertEquals("7482", PinyinEngine.t9Mixed("shua"));
    }
}
