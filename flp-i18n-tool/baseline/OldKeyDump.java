import com.kaffatech.mocha.common.util.ClassUtils;
import com.kaffatech.mocha.generator.i18n.I18nGenerator;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OldKeyDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: OldKeyDump <pkg[,pkg...]> <output.json>");
            System.exit(2);
        }
        List<String> packages = Arrays.asList(args[0].split(","));
        Map<String, Map<String, String>> code =
                I18nGenerator.generateCodeJsonByClassesPackage(packages);
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (String pkg : packages) {
            classes.addAll(ClassUtils.getClasses(pkg));
        }
        Map<String, Map<String, String>> enums = I18nGenerator.generateJsonByClasses(classes);

        String json = "{\"code\":" + keys(code.get("zh_CN"))
                + ",\"enum\":" + keys(enums.get("zh_CN")) + "}";
        Writer writer = new OutputStreamWriter(new FileOutputStream(args[1]), StandardCharsets.UTF_8);
        writer.write(json);
        writer.close();
        System.out.println("written " + args[1]
                + " code=" + size(code.get("zh_CN")) + " enum=" + size(enums.get("zh_CN")));
    }

    private static int size(Map<String, String> map) {
        return map == null ? 0 : map.size();
    }

    private static String keys(Map<String, String> map) {
        List<String> list = new ArrayList<String>(
                map == null ? Collections.<String>emptySet() : map.keySet());
        Collections.sort(list);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append(']').toString();
    }
}
