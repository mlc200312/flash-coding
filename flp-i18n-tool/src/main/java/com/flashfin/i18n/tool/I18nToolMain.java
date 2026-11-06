package com.flashfin.i18n.tool;

import com.flashfin.i18n.tool.cli.CliArguments;
import com.flashfin.i18n.tool.cli.CliException;
import com.flashfin.i18n.tool.cli.CommandResult;
import com.flashfin.i18n.tool.cli.DiffCommand;
import com.flashfin.i18n.tool.cli.DownloadCommand;
import com.flashfin.i18n.tool.cli.ExitCode;
import com.flashfin.i18n.tool.cli.ScanCommand;
import com.flashfin.i18n.tool.cli.StatusCommand;
import com.flashfin.i18n.tool.cli.UploadCommand;
import com.flashfin.i18n.tool.cli.ValidateCommand;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.report.Reporter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class I18nToolMain {

    private static final String USAGE =
            "java -jar flp-i18n-cli.jar <status|scan|diff|validate|upload|download> "
                    + "[--config <modules.yaml>] [--module <name>] [--os <os>] "
                    + "[--languages zh_CN,en] [--apply --plan-id <sha256>] [--limit <n>] "
                    + "[--rate-ms <ms>] [--force] [--json]";

    public static void main(String[] args) {
        PrintStream out;
        try {
            out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            out = System.out;
        }
        int exitCode = new I18nToolMain().run(args, System.getenv(), out);
        System.exit(exitCode);
    }

    public int run(String[] args, Map<String, String> env, PrintStream out) {
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (CliException e) {
            write(out, JsonEnvelope.failure(null, null,
                    new ErrorInfo("INVALID_ARGUMENT", e.getMessage(), USAGE, false)));
            return ExitCode.INVALID;
        }

        if (arguments.isHelp()) {
            write(out, JsonEnvelope.success("help", helpData()));
            return ExitCode.OK;
        }

        CommandResult result;
        try {
            if ("status".equals(arguments.getCommand())) {
                result = StatusCommand.defaults(env).execute(arguments);
            } else if ("scan".equals(arguments.getCommand())) {
                result = ScanCommand.defaults(env).execute(arguments);
            } else if ("diff".equals(arguments.getCommand())) {
                result = DiffCommand.defaults(env).execute(arguments);
            } else if ("validate".equals(arguments.getCommand())) {
                result = ValidateCommand.defaults(env).execute(arguments);
            } else if ("upload".equals(arguments.getCommand())) {
                result = UploadCommand.defaults(env).execute(arguments);
            } else if ("download".equals(arguments.getCommand())) {
                result = DownloadCommand.defaults(env).execute(arguments);
            } else {
                result = new CommandResult(ExitCode.INVALID, JsonEnvelope.failure(arguments.getCommand(), null,
                        new ErrorInfo("COMMAND_NOT_IMPLEMENTED",
                                "命令尚未实现: " + arguments.getCommand(),
                                "当前已实现 status/scan/diff/validate/upload/download",
                                false)));
            }
        } catch (RuntimeException e) {
            result = new CommandResult(ExitCode.INTERNAL_ERROR, JsonEnvelope.failure(arguments.getCommand(), null,
                    new ErrorInfo("INTERNAL_ERROR", "内部错误: " + e.getClass().getSimpleName(),
                            "请记录复现步骤并反馈", false)));
        }

        write(out, result.getEnvelope());
        return result.getExitCode();
    }

    private Map<String, Object> helpData() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("usage", USAGE);
        data.put("commands", Arrays.asList("status", "scan", "diff", "validate", "upload",
                "download"));
        Map<String, Object> exitCodes = new LinkedHashMap<String, Object>();
        exitCodes.put("0", "成功");
        exitCodes.put("2", "参数或配置错误");
        exitCodes.put("3", "网络或平台错误");
        exitCodes.put("4", "写操作被拒绝（未确认或工作区脏）");
        data.put("exitCodes", exitCodes);
        data.put("env", Arrays.asList("FLP_I18N_TOKEN", StatusCommand.ENV_CONFIG,
                com.flashfin.i18n.tool.fs.PathGuard.ENV_ALLOWED_ROOTS));
        return data;
    }

    private void write(PrintStream out, JsonEnvelope envelope) {
        String json;
        try {
            json = Reporter.toJson(envelope);
        } catch (RuntimeException e) {
            json = "{\"ok\":false,\"command\":null,\"data\":null,\"warnings\":[],"
                    + "\"error\":{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"输出序列化失败\","
                    + "\"hint\":\"请反馈该问题\",\"retryable\":false}}";
        }
        out.println(json);
        out.flush();
    }
}
