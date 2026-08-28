package com.rigour.sales.temporarycheckin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 临时打卡后台登录、改密、账号创建和一次性引导模型。 */
final class TemporaryCheckinAdminAuthModels {

    private TemporaryCheckinAdminAuthModels() { }

    record LoginRequest(String username, String password) {
        @Override public String toString() { return "LoginRequest[username=" + username + ", password=***]"; }
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
        @Override public String toString() { return "ChangePasswordRequest[currentPassword=***, newPassword=***]"; }
    }

    record CreateCityAdminRequest(String username, String displayName, String city) { }

    record CreateCityRequest(String name, String adminUsername) { }

    record CityView(UUID id, String name, String status, int sortOrder) { }

    record AdminAccountView(
            UUID accountId,
            String username,
            String displayName,
            String role,
            String city,
            String status,
            boolean mustChangePassword) { }

    record CreateCityResponse(CityView city, TemporaryCredentialView administrator) { }

    record AdminMeView(
            UUID accountId,
            String username,
            String displayName,
            String role,
            String city,
            boolean allCities,
            boolean mustChangePassword,
            boolean canManageSalespersons,
            boolean canManageCities,
            String csrfToken) { }

    record LoginResponse(AdminMeView account) { }

    record TemporaryCredentialView(
            UUID accountId,
            String username,
            String displayName,
            String role,
            String city,
            String temporaryPassword,
            Instant expiresAt) {
        @Override public String toString() {
            return "TemporaryCredentialView[accountId=" + accountId + ", username=" + username
                    + ", temporaryPassword=***, expiresAt=" + expiresAt + "]";
        }
    }

    record BootstrapAdminRequest(String username, String displayName, String role, String city) { }

    record BootstrapRequest(List<String> cities, List<BootstrapAdminRequest> accounts) { }

    record BootstrapResponse(List<String> cities, List<TemporaryCredentialView> createdAccounts) { }
}
