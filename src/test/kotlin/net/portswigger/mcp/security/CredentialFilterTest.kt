package net.portswigger.mcp.security

import burp.api.montoya.MontoyaApi
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.logging.Logging
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import io.mockk.mockk
import io.mockk.every
import kotlinx.serialization.json.*

class CredentialFilterTest {

    private lateinit var config: McpConfig
    private lateinit var api: MontoyaApi
    private lateinit var mockLogging: Logging
    private lateinit var persistedObject: PersistedObject
    private lateinit var projectOptionString: String
    private lateinit var usersOptionString: String

    @BeforeEach
    fun setUp() {
        api = mockk<MontoyaApi>()
        mockLogging = mockk<Logging>()
        persistedObject = mockk<PersistedObject>()
        val storage = mutableMapOf<String, Any>(
            "enabled" to true,
            "configEditingTooling" to false,
            "requireHttpRequestApproval" to true,
            "host" to "127.0.0.1",
            "_autoApproveTargets" to "",
            "port" to 9876
        )

        persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers { storage[firstArg()] as? Boolean ?: false }
            every { getString(any()) } answers { storage[firstArg()] as? String ?: "" }
            every { getInteger(any()) } answers { storage[firstArg()] as? Int ?: 0 }
            every { setBoolean(any(), any()) } answers {
                storage[firstArg()] = secondArg<Boolean>()
            }
            every { setString(any(), any()) } answers {
                storage[firstArg()] = secondArg<String>()
            }
            every { setInteger(any(), any()) } answers {
                storage[firstArg()] = secondArg<Int>()
            }
        }

        mockLogging = mockk<Logging>().apply {
            every { logToError(any<String>()) } returns Unit
        }

        this.get_user_options_with_customizable_field()
        this.get_project_options_with_customizable_field()
        config = McpConfig(persistedObject, mockLogging)
    }

    fun get_user_options_with_customizable_field(username: String = "", password: String = ""): String {
        this.usersOptionString = """
        {
            "user_options": {
                "bchecks": {},
                "connections": {
                    "platform_authentication": {
                        "credentials": [
                            {
                                "username": "$username",
                                "password": "$password"
                            }
                        ],
                        "do_platform_authentication": false,
                        "prompt_on_authentication_failure": false
                    },
                    "socks_proxy": {
                        "username": "$username",
                        "password": "$password"
                    },
                    "upstream_proxy": {
                        "servers": []
                    }
                },
                "display": {},
                "extender": {},
                "intruder": {},
                "misc": {},
                "proxy": {},
                "repeater": {},
                "ssl": {}
            }
        }
        """.trimIndent()
        return this.usersOptionString
    }

    fun get_project_options_with_customizable_field(username: String = "", password: String = ""): String {
        this.projectOptionString = """
        {
            "bambda": {},
            "logger": {},
            "organiser": {},
            "project_options": {
                "connections": {
                    "out_of_scope_requests": {},
                    "platform_authentication": {
                        "credentials": [
                            {
                                "username": "$username",
                                "password": "$password"
                            }
                        ],
                        "do_platform_authentication": false,
                        "prompt_on_authentication_failure": false,
                        "use_user_options": true
                    },
                    "socks_proxy": {
                        "username": "$username",
                        "password": "$password"
                    },
                    "timeouts": {
                        "connect_timeout": 5000,
                        "read_timeout": 5000
                    },
                    "upstream_proxy": {
                        "servers": [],
                        "use_user_options": true
                    }
                },
                "dns": {},
                "http": {},
                "misc": {},
                "sessions": {},
                "ssl": {}
            },
            "proxy": {},
            "repeater": {},
            "sequencer": {},
            "target": {}
        }
        """.trimIndent()
       return this.projectOptionString
    }

    @Test
    fun `test security filter on project_options `() {
        config.filterConfigCredentials = true
        projectOptionString = get_project_options_with_customizable_field("testuser", "testpass")
        val filteredProjectJson = filterConfigCredentials(projectOptionString)
        val parsedJson = Json.parseToJsonElement(filteredProjectJson).jsonObject

        val credentials = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }

        socks_proxy?.let {
            Assertions.assertEquals("*****", socks_proxy["password"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test security filter on user_options`() {
        config.filterConfigCredentials = true
        usersOptionString = get_user_options_with_customizable_field("testuser", "testpass")
        val filteredUserJson = filterConfigCredentials(usersOptionString)
        val parsedJson = Json.parseToJsonElement(filteredUserJson).jsonObject

        val credentials = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }

        socks_proxy?.let {
            Assertions.assertEquals("*****", it["password"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test security filter with empty credentials on user_options`() {
        config.filterConfigCredentials = true
        val empty_user_credentials: String = get_user_options_with_customizable_field("", "")
        val filteredJson = filterConfigCredentials(empty_user_credentials)
        val parsedJson = Json.parseToJsonElement(filteredJson).jsonObject

        val credentials = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertTrue(credentialObj["username"]?.jsonPrimitive?.content.isNullOrEmpty())
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }
        socks_proxy?.let {
            Assertions.assertTrue(socks_proxy["username"]?.jsonPrimitive?.content.isNullOrEmpty())
            Assertions.assertEquals("*****", socks_proxy["password"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test security filter with empty credentials on project_options`() {
        config.filterConfigCredentials = true
        val empty_project_credentials = get_project_options_with_customizable_field("", "")
        val filteredJson = filterConfigCredentials(empty_project_credentials)
        val parsedJson = Json.parseToJsonElement(filteredJson).jsonObject

        val credentials = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertTrue(credentialObj["username"]?.jsonPrimitive?.content.isNullOrEmpty())
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }
        socks_proxy?.let {
            Assertions.assertTrue(socks_proxy["username"]?.jsonPrimitive?.content.isNullOrEmpty())
            Assertions.assertEquals("*****", socks_proxy["password"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test security filter with malformed Json on user_options`() {
        config.filterConfigCredentials = true
        val malformedJson = """
        {
            "user_options": {
                "bchecks": {},
                "connections": {
                    "platform_authentication": {
                        "credentials": []
                    },
                    "socks_proxy": { "password": "" 
                },
                "display": {},
                "extender": {},
                "intruder": {},
                "misc": {},
                "proxy": {},
                "repeater": {},
                "ssl": {}
            }
        }
        """.trimIndent()
        val exception = Assertions.assertThrows(RuntimeException::class.java) {
            filterConfigCredentials(malformedJson)
        }
        Assertions.assertEquals("Failed to filter credentials", exception.message)
        Assertions.assertNotNull(exception.cause)
    }

    @Test
    fun `test security filter with malformed Json on project_options`() {
        config.filterConfigCredentials = true
        val malformedJson = """
        {
            "bambda": {},
            "logger": {},
            "organiser": {},
            "project_options": {
                "connections": {
                    "platform_authentication": {
                        "credentials": []
                    },
                    "socks_proxy": { "password": "" }
                }
            },
            "proxy": {},
            "repeater": {},
            "sequencer": {},
            "target": {}
        
        """.trimIndent()
        val exception = Assertions.assertThrows(RuntimeException::class.java) {
            filterConfigCredentials(malformedJson)
        }
        Assertions.assertEquals("Failed to filter credentials", exception.message)
        Assertions.assertNotNull(exception.cause)
    }

    @Test
    fun `test security filter with username but no password on user_options`() {
        config.filterConfigCredentials = true
        val jsonWithUsernameOnly = get_user_options_with_customizable_field("testuser", "")
        val filteredJson = filterConfigCredentials(jsonWithUsernameOnly)
        val parsedJson = Json.parseToJsonElement(filteredJson).jsonObject

        val credentials = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["user_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }

        socks_proxy?.let {
            Assertions.assertEquals("*****", socks_proxy["password"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test security filter with username but no password on project_options`() {
        config.filterConfigCredentials = true
        val jsonWithUsernameOnly = get_project_options_with_customizable_field("testuser", "")
        val filteredJson = filterConfigCredentials(jsonWithUsernameOnly)
        val parsedJson = Json.parseToJsonElement(filteredJson).jsonObject

        val credentials = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("platform_authentication")?.jsonObject
            ?.get("credentials")?.jsonArray

        val socks_proxy = parsedJson["project_options"]?.jsonObject
            ?.get("connections")?.jsonObject
            ?.get("socks_proxy")?.jsonObject

        credentials?.forEach { credential ->
            val credentialObj = credential.jsonObject
            Assertions.assertEquals("*****", credentialObj["password"]?.jsonPrimitive?.content)
        }
        socks_proxy?.let {
            Assertions.assertEquals("*****", socks_proxy["password"]?.jsonPrimitive?.content)
        }
    }
}