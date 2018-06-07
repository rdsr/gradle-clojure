package gradle_clojure.plugin.clojure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import javax.inject.Inject;

import gradle_clojure.plugin.clojure.internal.DefaultClojureSourceSet;
import gradle_clojure.plugin.clojure.tasks.ClojureCheck;
import gradle_clojure.plugin.clojure.tasks.ClojureCompile;
import gradle_clojure.plugin.clojure.tasks.ClojureSourceSet;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentModuleMetadataDetails;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.SourceDirectorySetFactory;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public class ClojureBasePlugin implements Plugin<Project> {
  public static final String TOOLS_CONFIGURATION_NAME = "clojureTools";
  public static final String NREPL_CONFIGURATION_NAME = "nrepl";

  private final SourceDirectorySetFactory sourceDirectorySetFactory;

  @Inject
  public ClojureBasePlugin(SourceDirectorySetFactory sourceDirectorySetFactory) {
    this.sourceDirectorySetFactory = sourceDirectorySetFactory;
  }

  @Override
  public void apply(Project project) {
    project.getPluginManager().apply(JavaBasePlugin.class);

    configureSourceSetDefaults(project);
    configureModuleReplacements(project);
  }

  private void configureSourceSetDefaults(Project project) {
    Configuration tools = project.getConfigurations().create(TOOLS_CONFIGURATION_NAME);
    tools.defaultDependencies(deps -> {
      deps.add(project.getDependencies().create("io.github.gradle-clojure:gradle-clojure-tools:" + getVersion()));
    });
    Configuration nrepl = project.getConfigurations().create(NREPL_CONFIGURATION_NAME);
    nrepl.defaultDependencies(deps -> {
      deps.add(project.getDependencies().create("nrepl:nrepl:0.3.1"));
    });

    project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().all(sourceSet -> {
      ClojureSourceSet clojureSourceSet = new DefaultClojureSourceSet("clojure", sourceDirectorySetFactory);
      new DslObject(sourceSet).getConvention().getPlugins().put("clojure", clojureSourceSet);

      project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName()).extendsFrom(tools);
      project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()).extendsFrom(tools);

      clojureSourceSet.getClojure().srcDir(String.format("src/%s/clojure", sourceSet.getName()));
      // in case the clojure source overlaps with the resources source, exclude any clojure code
      // from resources
      sourceSet.getResources().getFilter().exclude(element -> clojureSourceSet.getClojure().contains(element.getFile()));
      sourceSet.getAllSource().source(clojureSourceSet.getClojure());

      Provider<FileCollection> classpath = project.provider(() -> {
        return sourceSet.getCompileClasspath()
            .plus(project.files(sourceSet.getJava().getOutputDir()))
            .plus(project.files(sourceSet.getOutput().getResourcesDir()));
      });

      String checkTaskName = sourceSet.getTaskName("check", "clojure");
      ClojureCheck check = project.getTasks().create(checkTaskName, ClojureCheck.class);
      check.setDescription(String.format("Checks the %s Clojure source.", sourceSet.getName()));
      check.setSource(clojureSourceSet.getClojure());
      check.getClasspath().from(project.files(classpath));
      sourceSet.getOutput().dir(project.files(project.provider(() -> clojureSourceSet.getClojure().getSrcDirs())));

      check.dependsOn(sourceSet.getClassesTaskName());
      project.getTasks().getByName(LifecycleBasePlugin.CHECK_TASK_NAME).dependsOn(check);

      String compileTaskName = sourceSet.getCompileTaskName("clojure");
      ClojureCompile compile = project.getTasks().create(compileTaskName, ClojureCompile.class);
      compile.setDescription(String.format("Compiles the %s Clojure source.", sourceSet.getName()));
      compile.setSource(clojureSourceSet.getClojure());
      compile.getClasspath().from(project.files(classpath));

      DirectoryProperty buildDir = project.getLayout().getBuildDirectory();
      String outputDirPath = String.format("classes/%s/%s", clojureSourceSet.getClojure().getName(), sourceSet.getName());
      Provider<Directory> outputDir = buildDir.dir(outputDirPath);
      compile.getDestinationDir().set(outputDir);
      compile.dependsOn(sourceSet.getClassesTaskName());

      String classesAotName = String.format("%sAot", sourceSet.getClassesTaskName());
      project.getTasks().create(classesAotName, task -> {
        task.dependsOn(compile);
        task.dependsOn(sourceSet.getClassesTaskName());
      });
    });
  }

  private void configureModuleReplacements(Project project) {
    project.getDependencies().getModules().module("org.clojure:tools.nrepl", module -> {
      ComponentModuleMetadataDetails details = (ComponentModuleMetadataDetails) module;
      details.replacedBy("nrepl:nrepl", "nREPL was moved out of Clojure Contrib to its own project.");
    });
  }

  private String getVersion() {
    try (InputStream stream = ClojureBasePlugin.class.getResourceAsStream("/gradle-clojure.properties")) {
      Properties props = new Properties();
      props.load(stream);
      return props.getProperty("version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}