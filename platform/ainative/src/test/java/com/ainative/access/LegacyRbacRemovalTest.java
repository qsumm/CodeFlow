package com.ainative.access;

import com.ainative.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacyRbacRemovalTest {

    private static final Set<String> LEGACY_ERROR_CODES = Set.of(
            "ROLE_NOT_FOUND",
            "ROLE_BUILTIN_NO_DELETE",
            "ROLE_BUILTIN_NO_EDIT_PERMS",
            "ROLE_NAME_DUPLICATE",
            "ROLE_INVALID_PERMISSION",
            "MEMBER_IS_OWNER");

    @Test
    void legacyRbacTypeSourcesAreRemoved() {
        for (String className : Set.of(
                "com.ainative.rbac.PermissionAspect",
                "com.ainative.rbac.PermissionRegistry",
                "com.ainative.rbac.PermissionService",
                "com.ainative.rbac.RbacController",
                "com.ainative.rbac.RbacService",
                "com.ainative.rbac.RequirePerm",
                "com.ainative.rbac.RoleSeeder",
                "com.ainative.rbac.dto.CreateRoleRequest",
                "com.ainative.rbac.dto.PermissionGroupVO",
                "com.ainative.rbac.dto.RoleVO",
                "com.ainative.rbac.dto.UpdateRoleRequest",
                "com.ainative.rbac.model.MemberRoleDO",
                "com.ainative.rbac.model.MemberRoleDao",
                "com.ainative.rbac.model.RoleDO",
                "com.ainative.rbac.model.RoleDao",
                "com.ainative.rbac.model.RolePermissionDO",
                "com.ainative.rbac.model.RolePermissionDao")) {
            Path source = Path.of("src/main/java",
                    className.replace('.', '/') + ".java");
            assertFalse(Files.exists(source), className);
        }
    }

    @Test
    void legacyRbacEndpointsAreRemoved() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            String productionSources = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(LegacyRbacRemovalTest::readString)
                    .collect(Collectors.joining("\n"));

            assertFalse(productionSources.contains("/api/roles"), "/api/roles");
            assertFalse(productionSources.contains("/api/permissions"), "/api/permissions");
        }
    }

    @Test
    void legacyRbacMappersAreRemoved() {
        for (String filename : Set.of(
                "MemberRoleDao.xml",
                "RoleDao.xml",
                "RolePermissionDao.xml")) {
            assertFalse(Files.exists(Path.of("src/main/resources/mapping", filename)),
                    filename);
        }
    }

    @Test
    void legacyRoleErrorsAreRemoved() {
        Set<String> errorCodes = Stream.of(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertFalse(errorCodes.stream().anyMatch(LEGACY_ERROR_CODES::contains),
                () -> "legacy role errors remain: " + errorCodes.stream()
                        .filter(LEGACY_ERROR_CODES::contains)
                        .sorted()
                        .toList());
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
