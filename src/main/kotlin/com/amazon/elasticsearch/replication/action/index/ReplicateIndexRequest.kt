/*
 *   Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.elasticsearch.replication.action.index

import com.amazon.elasticsearch.replication.metadata.store.KEY_SETTINGS
import com.amazon.elasticsearch.replication.util.ValidationUtil.validateName
import org.elasticsearch.action.ActionRequestValidationException
import org.elasticsearch.action.IndicesRequest
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.support.master.AcknowledgedRequest
import org.elasticsearch.common.ParseField
import org.elasticsearch.common.io.stream.StreamInput
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.ObjectParser
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.ToXContent.Params
import org.elasticsearch.common.xcontent.ToXContentObject
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentParser
import java.io.IOException
import java.util.Collections
import java.util.function.BiConsumer
import java.util.function.BiFunction
import kotlin.collections.HashMap

class ReplicateIndexRequest : AcknowledgedRequest<ReplicateIndexRequest>, IndicesRequest.Replaceable, ToXContentObject {

    lateinit var followerIndex: String
    lateinit var leaderAlias: String
    lateinit var leaderIndex: String
    var assumeRoles: HashMap<String, String>? = null // roles to assume - {leader_fgac_role: role1, follower_fgac_role: role2}
    // Used for integ tests to wait until the restore from leader cluster completes
    var waitForRestore: Boolean = false
    // Triggered from autofollow to skip permissions check based on user as this is already validated
    var isAutoFollowRequest: Boolean = false

    var settings :Settings = Settings.EMPTY

    private constructor() {
    }

    constructor(followerIndex: String, leaderAlias: String, leaderIndex: String, settings: Settings = Settings.EMPTY) : super() {
        this.followerIndex = followerIndex
        this.leaderAlias = leaderAlias
        this.leaderIndex = leaderIndex
        this.settings = settings
    }

    companion object {
        const val LEADER_CLUSTER_ROLE = "leader_cluster_role"
        const val FOLLOWER_CLUSTER_ROLE = "follower_cluster_role"
        private val INDEX_REQ_PARSER = ObjectParser<ReplicateIndexRequest, Void>("FollowIndexRequestParser") { ReplicateIndexRequest() }
        val FGAC_ROLES_PARSER = ObjectParser<HashMap<String, String>, Void>("AssumeRolesParser") { HashMap() }
        init {
            FGAC_ROLES_PARSER.declareStringOrNull({assumeRoles: HashMap<String, String>, role: String -> assumeRoles[LEADER_CLUSTER_ROLE] = role},
                    ParseField(LEADER_CLUSTER_ROLE))
            FGAC_ROLES_PARSER.declareStringOrNull({assumeRoles: HashMap<String, String>, role: String -> assumeRoles[FOLLOWER_CLUSTER_ROLE] = role},
                    ParseField(FOLLOWER_CLUSTER_ROLE))

            INDEX_REQ_PARSER.declareString(ReplicateIndexRequest::leaderAlias::set, ParseField("leader_alias"))
            INDEX_REQ_PARSER.declareString(ReplicateIndexRequest::leaderIndex::set, ParseField("leader_index"))
            INDEX_REQ_PARSER.declareObjectOrDefault(BiConsumer {reqParser: ReplicateIndexRequest, roles: HashMap<String, String> -> reqParser.assumeRoles = roles},
                    FGAC_ROLES_PARSER, null, ParseField("assume_roles"))
            INDEX_REQ_PARSER.declareObjectOrDefault(BiConsumer{ request: ReplicateIndexRequest, settings: Settings -> request.settings = settings}, BiFunction{ p: XContentParser?, c: Void? -> Settings.fromXContent(p) },
                    null, ParseField(KEY_SETTINGS))
        }

        @Throws(IOException::class)
        fun fromXContent(parser: XContentParser, followerIndex: String): ReplicateIndexRequest {
            val followIndexRequest = INDEX_REQ_PARSER.parse(parser, null)
            followIndexRequest.followerIndex = followerIndex
            if(followIndexRequest.assumeRoles?.size == 0) {
                followIndexRequest.assumeRoles = null
            }

            if (followIndexRequest.settings == null) {
                followIndexRequest.settings = Settings.EMPTY
            }
            return followIndexRequest
        }
    }

    override fun validate(): ActionRequestValidationException? {

        var validationException = ActionRequestValidationException()
        if (!this::leaderAlias.isInitialized ||
            !this::leaderIndex.isInitialized ||
            !this::followerIndex.isInitialized) {
            validationException.addValidationError("Mandatory params are missing for the request")
        }

        validateName(leaderIndex, validationException)
        validateName(followerIndex, validationException)

        if(assumeRoles != null && (assumeRoles!!.size < 2 || assumeRoles!![LEADER_CLUSTER_ROLE] == null ||
                assumeRoles!![FOLLOWER_CLUSTER_ROLE] == null)) {
            validationException.addValidationError("Need roles for $LEADER_CLUSTER_ROLE and $FOLLOWER_CLUSTER_ROLE")
        }
        return if(validationException.validationErrors().isEmpty()) return null else validationException
    }

    override fun indices(vararg indices: String?): IndicesRequest {
        return this
    }

    override fun indices(): Array<String?> {
        return arrayOf(followerIndex)
    }

    override fun indicesOptions(): IndicesOptions {
        return IndicesOptions.strictSingleIndexNoExpandForbidClosed()
    }

    constructor(inp: StreamInput) : super(inp) {
        leaderAlias = inp.readString()
        leaderIndex = inp.readString()
        followerIndex = inp.readString()

        var leaderClusterRole = inp.readOptionalString()
        var followerClusterRole = inp.readOptionalString()
        assumeRoles = HashMap()
        if(leaderClusterRole != null) assumeRoles!![LEADER_CLUSTER_ROLE] = leaderClusterRole
        if(followerClusterRole != null) assumeRoles!![FOLLOWER_CLUSTER_ROLE] = followerClusterRole

        waitForRestore = inp.readBoolean()
        isAutoFollowRequest = inp.readBoolean()
        settings = Settings.readSettingsFromStream(inp)

    }

    override fun writeTo(out: StreamOutput) {
        super.writeTo(out)
        out.writeString(leaderAlias)
        out.writeString(leaderIndex)
        out.writeString(followerIndex)
        out.writeOptionalString(assumeRoles?.get(LEADER_CLUSTER_ROLE))
        out.writeOptionalString(assumeRoles?.get(FOLLOWER_CLUSTER_ROLE))
        out.writeBoolean(waitForRestore)
        out.writeBoolean(isAutoFollowRequest)

        Settings.writeSettingsToStream(settings, out);
    }

    @Throws(IOException::class)
    override fun toXContent(builder: XContentBuilder, params: Params): XContentBuilder {
        builder.startObject()
        builder.field("leader_alias", leaderAlias)
        builder.field("leader_index", leaderIndex)
        builder.field("follower_index", followerIndex)
        if(assumeRoles != null && assumeRoles!!.size == 2) {
            builder.field("assume_roles")
            builder.startObject()
            builder.field(LEADER_CLUSTER_ROLE, assumeRoles!![LEADER_CLUSTER_ROLE])
            builder.field(FOLLOWER_CLUSTER_ROLE, assumeRoles!![FOLLOWER_CLUSTER_ROLE])
            builder.endObject()
        }
        builder.field("wait_for_restore", waitForRestore)
        builder.field("is_autofollow_request", isAutoFollowRequest)

        builder.startObject(KEY_SETTINGS)
        settings.toXContent(builder, ToXContent.MapParams(Collections.singletonMap("flat_settings", "true")));
        builder.endObject()

        builder.endObject()

        return builder
    }
}
