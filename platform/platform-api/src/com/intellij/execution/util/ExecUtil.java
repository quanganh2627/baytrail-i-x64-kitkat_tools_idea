/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution.util;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ExecUtil {
  private static final NotNullLazyValue<Boolean> hasGkSudo = new NotNullLazyValue<Boolean>() {
    @NotNull
    @Override
    protected Boolean compute() {
      return new File("/usr/bin/gksudo").canExecute();
    }
  };

  private static final NotNullLazyValue<Boolean> hasKdeSudo = new NotNullLazyValue<Boolean>() {
    @NotNull
    @Override
    protected Boolean compute() {
      return new File("/usr/bin/kdesudo").canExecute();
    }
  };

  private static final NotNullLazyValue<Boolean> hasGnomeTerminal = new NotNullLazyValue<Boolean>() {
    @NotNull
    @Override
    protected Boolean compute() {
      return new File("/usr/bin/gnome-terminal").canExecute();
    }
  };

  private static final NotNullLazyValue<Boolean> hasKdeTerminal = new NotNullLazyValue<Boolean>() {
    @NotNull
    @Override
    protected Boolean compute() {
      return new File("/usr/bin/konsole").canExecute();
    }
  };

  private static final NotNullLazyValue<Boolean> hasXTerm = new NotNullLazyValue<Boolean>() {
    @NotNull
    @Override
    protected Boolean compute() {
      return new File("/usr/bin/xterm").canExecute();
    }
  };

  private ExecUtil() { }

  public static int execAndGetResult(final String... command) throws ExecutionException, InterruptedException {
    assert command != null && command.length > 0;
    return execAndGetResult(Arrays.asList(command));
  }

  public static int execAndGetResult(@NotNull final List<String> command) throws ExecutionException, InterruptedException {
    assert command.size() > 0;
    final GeneralCommandLine commandLine = new GeneralCommandLine(command);
    final Process process = commandLine.createProcess();
    return process.waitFor();
  }

  @NotNull
  public static String loadTemplate(@NotNull final ClassLoader loader,
                                    @NotNull final String templateName,
                                    @Nullable final Map<String, String> variables) throws IOException {
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed") final InputStream stream = loader.getResourceAsStream(templateName);
    if (stream == null) {
      throw new IOException("Template '" + templateName + "' not found by " + loader);
    }

    final String template = FileUtil.loadTextAndClose(stream);
    if (variables == null || variables.size() == 0) {
      return template;
    }

    final StringBuilder buffer = new StringBuilder(template);
    for (Map.Entry<String, String> var : variables.entrySet()) {
      final String name = var.getKey();
      final int pos = buffer.indexOf(name);
      if (pos >= 0) {
        buffer.replace(pos, pos + name.length(), var.getValue());
      }
    }
    return buffer.toString();
  }

  @NotNull
  public static File createTempExecutableScript(@NotNull final String prefix,
                                                @NotNull final String suffix,
                                                @NotNull final String source) throws IOException, ExecutionException {
    final File tempFile = FileUtil.createTempFile(prefix, suffix);
    FileUtil.writeToFile(tempFile, source);
    if (!tempFile.setExecutable(true, true)) {
      throw new ExecutionException("Failed to make temp file executable: " + tempFile);
    }
    return tempFile;
  }

  @NotNull
  public static String getOsascriptPath() {
    return "/usr/bin/osascript";
  }

  @NotNull
  public static String getOpenCommandPath() {
    return "/usr/bin/open";
  }

  @NotNull
  public static String getWindowsShellName() {
    return SystemInfo.isWin2kOrNewer ? "cmd.exe" : "command.com";
  }

  @NotNull
  public static ProcessOutput execAndGetOutput(@NotNull final List<String> command,
                                               @Nullable final String workDir) throws ExecutionException {
    assert command.size() > 0;
    final GeneralCommandLine commandLine = new GeneralCommandLine(command);
    commandLine.setWorkDirectory(workDir);
    final Process process = commandLine.createProcess();
    final CapturingProcessHandler processHandler = new CapturingProcessHandler(process);
    return processHandler.runProcess();
  }

  @Nullable
  public static String execAndReadLine(final String... command) {
    try {
      final Process process = new GeneralCommandLine(command).createProcess();
      final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      try {
        return reader.readLine();
      }
      finally {
        reader.close();
      }
    }
    catch (Exception ignored) { }
    return null;
  }

  @NotNull
  public static ProcessOutput sudoAndGetOutput(@NotNull final String scriptPath,
                                               @NotNull final String prompt) throws IOException, ExecutionException {
    return sudoAndGetOutput(scriptPath, prompt, null);
  }

  /**
   * @param scriptPath is already escaped file path
   */
  @NotNull
  public static ProcessOutput sudoAndGetOutput(@NotNull final String scriptPath,
                                               @NotNull final String prompt,
                                               @Nullable String workDir) throws IOException, ExecutionException {
    if (SystemInfo.isMac) {
      final String script = "do shell script \"" + scriptPath + "\" with administrator privileges";
      return execAndGetOutput(Arrays.asList(getOsascriptPath(), "-e", script), workDir);
    }
    else if ("root".equals(System.getenv("USER"))) {
      return execAndGetOutput(Collections.singletonList(scriptPath), workDir);
    }
    else if (hasKdeSudo.getValue()) {
      return execAndGetOutput(Arrays.asList("kdesudo", "--comment", prompt, scriptPath), workDir);
    }
    else if (hasGkSudo.getValue()) {
      return execAndGetOutput(Arrays.asList("gksudo", "--message", prompt, scriptPath), workDir);
    }
    else if (SystemInfo.isUnix && hasTerminalApp()) {
      final File sudo = createTempExecutableScript("sudo", ".sh",
                                                   "#!/bin/sh\n" +
                                                   "echo \"" + prompt + "\"\n" +
                                                   "echo\n" +
                                                   "sudo " + scriptPath + "\n" +
                                                   "STATUS=$?\n" +
                                                   "echo\n" +
                                                   "read -p \"Press Enter to close this window...\" TEMP\n" +
                                                   "exit $STATUS\n");
      return execAndGetOutput(getTerminalCommand("Install", sudo.getAbsolutePath()), workDir);
    }

    throw new UnsupportedSystemException();
  }

  public static int sudoAndGetResult(@NotNull final String scriptPath,
                                     @NotNull final String prompt) throws IOException, ExecutionException {
    return sudoAndGetOutput(scriptPath, prompt).getExitCode();
  }

  public static boolean hasTerminalApp() {
    return SystemInfo.isWindows || SystemInfo.isMac || hasKdeTerminal.getValue() || hasGnomeTerminal.getValue() || hasXTerm.getValue();
  }

  @NotNull
  public static List<String> getTerminalCommand(@Nullable String title, @NotNull String command) {
    if (SystemInfo.isWindows) {
      title = title != null ? title.replace("\"", "'") : "";
      return Arrays.asList(getWindowsShellName(), "/c", "start", GeneralCommandLine.inescapableQuote(title), command);
    }
    else if (SystemInfo.isMac) {
      return Arrays.asList(getOpenCommandPath(), "-a", "Terminal", command); // todo: title?
    }
    else if (hasKdeTerminal.getValue()) {
      return title != null ? Arrays.asList("/usr/bin/konsole", "-p", "tabtitle=\"" + title.replace("\"", "'") + "\"", "-e", command)
                           : Arrays.asList("/usr/bin/konsole", "-e", command);
    }
    else if (hasGnomeTerminal.getValue()) {
      return title != null ? Arrays.asList("/usr/bin/gnome-terminal", "-t", title, "-x", command)
                           : Arrays.asList("/usr/bin/gnome-terminal", "-x", command);
    }
    else if (hasXTerm.getValue()) {
      return title != null ? Arrays.asList("/usr/bin/xterm", "-T", title, "-e", command)
                           : Arrays.asList("/usr/bin/xterm", "-e", command);
    }

    throw new UnsupportedSystemException();
  }

  public static class UnsupportedSystemException extends UnsupportedOperationException {
    public UnsupportedSystemException() {
      super("Unsupported OS/desktop: " + SystemInfo.OS_NAME + '/' + SystemInfo.SUN_DESKTOP);
    }
  }
}
