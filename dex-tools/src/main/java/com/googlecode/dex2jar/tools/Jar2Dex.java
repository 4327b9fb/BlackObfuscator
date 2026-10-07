/*
 * dex2jar - Tools to work with android .dex and java .class files
 * Copyright (c) 2009-2012 Panxiaobo
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.dex2jar.tools;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

@BaseCmd.Syntax(cmd = "d2j-jar2dex", syntax = "[options] <dir>", desc = "Convert jar to dex by invoking D8.")
public class Jar2Dex extends BaseCmd {
    public static void main(String... args) {
        new Jar2Dex().doMain(args);
    }

    @Opt(opt = "f", longOpt = "force", hasArg = false, description = "force overwrite")
    private boolean forceOverwrite = false;
    @Opt(opt = "o", longOpt = "output", description = "output .dex file, default is $current_dir/[jar-name]-jar2dex.dex", argName = "out-dex-file")
    private Path output;
    @Opt(opt = "l", longOpt = "library", description = "android.jar (or other library jar) to add as D8 library classpath for interface desugaring", argName = "android-jar")
    private Path library;
    @Opt(opt = "min-api", longOpt = "min-api", description = "minimum API level for D8 dex output, default 21", argName = "min-api-level")
    private int minApi = 21;

    @Override
    protected void doCommandLine() throws Exception {
        if (remainingArgs.length != 1) {
            usage();
            return;
        }

        Path jar = new File(remainingArgs[0]).toPath();
        if (!Files.exists(jar)) {
            System.err.println(jar + " is not exists");
            usage();
            return;
        }

        if (output == null) {
            if (Files.isDirectory(jar)) {
                output = new File(jar.getFileName() + "-jar2dex.dex").toPath();
            } else {
                output = new File(getBaseName(jar.getFileName().toString()) + "-jar2dex.dex").toPath();
            }
        }

        if (Files.exists(output) && !forceOverwrite) {
            System.err.println(output + " exists, use --force to overwrite");
            usage();
            return;
        }

        Path tmp = null;
        final Path realJar;
        try {
            if (Files.isDirectory(jar)) {
                realJar = Files.createTempFile("d2j", ".jar");
                tmp = realJar;
                System.out.println("zipping " + jar + " -> " + realJar);
                try (FileSystem fs = createZip(realJar)) {
                    final Path outRoot = fs.getPath("/");
                    walkJarOrDir(jar, new FileVisitorX() {
                        @Override
                        public void visitFile(Path file, String relative) throws IOException {
                            if (file.getFileName().toString().endsWith(".class")) {
                                Files.copy(file, outRoot.resolve(relative));
                            }
                        }
                    });
                }
            } else {
                realJar = jar;
            }

            System.out.println("jar2dex " + realJar + " -> " + output);

            // Modern replacement of com.android.dx: use D8 (com.android.tools:r8).
            // The old code called com.android.dx.command.Main reflectively, which is
            // no longer shipped by AGP 7+/8+ and fails on compileSdk 31+ / JDK 17.
            // D8 only writes to an existing directory (or .zip/.jar), so we write to a
            // temp dir and move the produced classes.dex to the requested output file.
            Path outDir = output.toAbsolutePath().getParent();
            if (outDir != null) {
                Files.createDirectories(outDir);
            }
            Path d8Out = Files.createTempDirectory("d2j-jar2dex");
            try {
                D8Command.Builder builder = D8Command.builder()
                        .addProgramFiles(realJar)
                        .setOutput(d8Out, OutputMode.DexIndexed)
                        .setMinApiLevel(minApi);
                if (library != null && Files.exists(library)) {
                    builder.addLibraryFiles(library);
                }
                D8.run(builder.build());
                Path produced = d8Out.resolve("classes.dex");
                if (!Files.exists(produced)) {
                    // D8 may emit multiple dex files; keep only the first for this single-jar input
                    try (Stream<Path> s = Files.list(d8Out)) {
                        produced = s.filter(p -> p.getFileName().toString().endsWith(".dex"))
                                .findFirst().orElse(produced);
                    }
                }
                Files.deleteIfExists(output);
                Files.move(produced, output, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try (Stream<Path> s = Files.list(d8Out)) {
                    s.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
                Files.deleteIfExists(d8Out);
            }
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }

    }
}
