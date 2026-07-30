package com.rigour.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验仓库结构而非运行行为，防止重复模块、孤儿 POM 和领域服务直连重新进入脚手架。
 */
class ReactorArchitectureTest {

    private static final Path ROOT = Path.of(System.getProperty("repositoryRoot"))
            .toAbsolutePath()
            .normalize();

    @Test
    void everyPomBelongsToRootReactor() throws Exception {
        Set<String> expected = new TreeSet<>();
        expected.add(".");
        expected.addAll(allModules());

        Set<String> actual = new TreeSet<>();
        try (var paths = Files.walk(ROOT)) {
            paths.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .map(Path::getParent)
                    .map(ROOT::relativize)
                    .map(path -> path.toString().isEmpty() ? "." : path.toString())
                    .forEach(actual::add);
        }

        assertEquals(expected, actual, "所有 pom.xml 必须由根 reactor 聚合，且不得存在游离模块");
    }

    @Test
    void artifactIdsAreUniqueAndApplicationSetIsStable() throws Exception {
        Set<String> artifactIds = new HashSet<>();
        for (String module : allModules()) {
            String artifactId = directChildText(readPom(ROOT.resolve(module).resolve("pom.xml")), "artifactId");
            assertTrue(artifactIds.add(artifactId), () -> "重复 artifactId: " + artifactId);
        }

        Set<String> serviceModules = new TreeSet<>();
        rootModules().stream()
                .filter(module -> module.startsWith("services/"))
                .forEach(serviceModules::add);

        assertEquals(12, serviceModules.size(), "必须保持 Gateway + 11 个领域服务");
        assertTrue(serviceModules.contains("services/rigour-api-gateway"));
        assertTrue(serviceModules.stream().noneMatch(module -> module.equals("services/rigour-gateway")));
        assertEquals(12, applicationModules().size(), "聚合父模块和API模块不得被误计为启动应用");
    }

    @Test
    void servicesDoNotDependOnOtherServiceArtifacts() throws Exception {
        Set<String> serviceArtifacts = new HashSet<>();
        List<String> serviceModules = applicationModules();
        for (String module : serviceModules) {
            serviceArtifacts.add(directChildText(readPom(ROOT.resolve(module).resolve("pom.xml")), "artifactId"));
        }

        List<String> violations = new ArrayList<>();
        for (String module : serviceModules) {
            Document pom = readPom(ROOT.resolve(module).resolve("pom.xml"));
            for (Element dependency : directDependencies(pom)) {
                String groupId = directChildText(dependency, "groupId");
                String artifactId = directChildText(dependency, "artifactId");
                if ("com.rigour".equals(groupId) && serviceArtifacts.contains(artifactId)) {
                    violations.add(module + " -> " + artifactId);
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "领域服务禁止直接依赖其他服务: " + violations);
    }

    @Test
    void platformStarterContainsOnlyMandatorySharedModules() throws Exception {
        Document starterPom = readPom(ROOT.resolve("platform/rigour-platform-starter/pom.xml"));
        Set<String> internalDependencies = new TreeSet<>();
        for (Element dependency : directDependencies(starterPom)) {
            if ("com.rigour".equals(directChildText(dependency, "groupId"))) {
                internalDependencies.add(directChildText(dependency, "artifactId"));
            }
        }

        assertEquals(Set.of(
                "rigour-shared-context",
                "rigour-shared-core",
                "rigour-shared-logging"
        ), internalDependencies, "可选 shared 库不得由 platform-starter 强制传递");
    }

    @Test
    void topLevelModuleDirectoriesMatchReactor() throws Exception {
        assertEquals(moduleDirectories("shared"), reactorDirectories("shared"));
        assertEquals(moduleDirectories("services"), reactorDirectories("services"));
    }

    private List<String> rootModules() throws Exception {
        Document rootPom = readPom(ROOT.resolve("pom.xml"));
        Element modules = directChild(rootPom.getDocumentElement(), "modules");
        List<String> result = new ArrayList<>();
        for (Element module : directChildren(modules, "module")) {
            result.add(module.getTextContent().trim());
        }
        return result;
    }

    private List<String> allModules() throws Exception {
        Set<String> result = new LinkedHashSet<>();
        collectModules(Path.of(""), ROOT.resolve("pom.xml"), result);
        return List.copyOf(result);
    }

    private void collectModules(Path parent, Path pomPath, Set<String> result) throws Exception {
        Document pom = readPom(pomPath);
        Element modules = directChild(pom.getDocumentElement(), "modules");
        if (modules == null) {
            return;
        }
        for (Element module : directChildren(modules, "module")) {
            Path relativePath = parent.resolve(module.getTextContent().trim()).normalize();
            String modulePath = relativePath.toString();
            if (result.add(modulePath)) {
                collectModules(relativePath, ROOT.resolve(relativePath).resolve("pom.xml"), result);
            }
        }
    }

    private List<String> applicationModules() throws Exception {
        List<String> result = new ArrayList<>();
        for (String module : allModules()) {
            if (!module.startsWith("services/")) {
                continue;
            }
            Document pom = readPom(ROOT.resolve(module).resolve("pom.xml"));
            String packaging = directChildText(pom, "packaging");
            String artifactId = directChildText(pom, "artifactId");
            if (!"pom".equals(packaging)
                    && ("rigour-api-gateway".equals(artifactId) || artifactId.endsWith("-service"))) {
                result.add(module);
            }
        }
        return result;
    }

    private List<Element> directDependencies(Document pom) {
        Element dependencies = directChild(pom.getDocumentElement(), "dependencies");
        return dependencies == null ? List.of() : directChildren(dependencies, "dependency");
    }

    private Set<String> moduleDirectories(String group) throws Exception {
        Set<String> directories = new TreeSet<>();
        try (var paths = Files.list(ROOT.resolve(group))) {
            paths.filter(Files::isDirectory)
                    .map(ROOT::relativize)
                    .map(Path::toString)
                    .forEach(directories::add);
        }
        return directories;
    }

    private Set<String> reactorDirectories(String group) throws Exception {
        Set<String> directories = new TreeSet<>();
        rootModules().stream()
                .filter(module -> module.startsWith(group + "/"))
                .forEach(directories::add);
        return directories;
    }

    private Document readPom(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private String directChildText(Document document, String localName) {
        return directChildText(document.getDocumentElement(), localName);
    }

    private String directChildText(Element parent, String localName) {
        Element child = directChild(parent, localName);
        return child == null ? "" : child.getTextContent().trim();
    }

    private Element directChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        return directChildren(parent, localName).stream().findFirst().orElse(null);
    }

    private List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }
}
