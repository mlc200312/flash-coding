import com.kaffatech.mocha.common.util.ClassUtils;
import com.kaffatech.mocha.generator.i18n.I18nGenerator;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BaselineScan {
    public static void main(String[] args) throws Exception {
        String pkg = "com.flashfin.base.business.domain.type";
        Map<String, Map<String, String>> code = I18nGenerator.generateCodeJsonByClassesPackage(Collections.singletonList(pkg));
        List<Class<?>> classes = ClassUtils.getClasses(pkg);
        Map<String, Map<String, String>> enums = I18nGenerator.generateJsonByClasses(classes);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"module\": \"flm-flp-base-business\",\n");
        sb.append("  \"os\": \"flp-code\",\n");
        sb.append("  \"package\": \"").append(pkg).append("\",\n");
        sb.append("  \"generatedAt\": \"").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())).append("\",\n");
        sb.append("  \"source\": {\n");
        sb.append("    \"upload\": \"FlmDictionariesApplicationTests#exportFlpCodeToPlatform/exportFlpEnumToPlatform\",\n");
        sb.append("    \"keyRule\": \"code_<code> for ResultCode; <EnumSimpleName>_<code> for Enumerable\"\n");
        sb.append("  },\n");
        sb.append("  \"classCount\": ").append(classes.size()).append(",\n");
        sb.append("  \"languages\": ").append(jsonArray(I18nGenerator.I18N_LIST)).append(",\n");
        sb.append("  \"codeKeys\": ").append(toJson(code)).append(",\n");
        sb.append("  \"enumKeys\": ").append(toJson(enums)).append(",\n");
        sb.append("  \"counts\": {");
        sb.append("\"code\": ").append(countOf(code)).append(", ");
        sb.append("\"enum\": ").append(countOf(enums));
        sb.append("}\n");
        sb.append("}\n");

        String out = args.length > 0 ? args[0] : "/tmp/flp-baseline/baseline.json";
        Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
        w.write(sb.toString());
        w.close();
        System.out.println("written " + out);
        System.out.println("classes=" + classes.size() + " code=" + countOf(code) + " enum=" + countOf(enums));
    }

    private static int countOf(Map<String, Map<String, String>> m) {
        Map<String, String> zh = m.get("zh_CN");
        return zh == null ? 0 : zh.size();
    }

    private static String jsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append('"').append(esc(list.get(i))).append('"');
        }
        return sb.append("]").toString();
    }

    private static String toJson(Map<String, Map<String, String>> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Map<String, String>> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":{");
            boolean f2 = true;
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                if (!f2) sb.append(",");
                f2 = false;
                sb.append('"').append(esc(kv.getKey())).append("\":\"").append(esc(kv.getValue())).append('"');
            }
            sb.append("}");
        }
        return sb.append("}").toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
