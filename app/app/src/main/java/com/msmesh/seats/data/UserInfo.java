package com.msmesh.seats.data;

import com.google.gson.annotations.SerializedName;

/** Claims returned by /connect/userinfo. MobileGwEndpoint selects the tenant's API host. */
public class UserInfo {
	@SerializedName("email") public String email;
	@SerializedName("preferred_username") public String preferredUsername;
	@SerializedName("TenantId") public String tenantId;
	@SerializedName("studentId") public String studentId;
	@SerializedName("MobileGwEndpoint") public String mobileGwEndpoint;
}