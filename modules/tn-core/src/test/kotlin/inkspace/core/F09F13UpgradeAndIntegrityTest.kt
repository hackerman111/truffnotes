package inkspace.core
import inkspace.core.codec.*
import inkspace.core.model.*
import inkspace.core.id.*
import inkspace.core.mutation.*
import inkspace.core.pages.*
import inkspace.core.payload.*
import inkspace.core.limits.Limits
import inkspace.core.unit.*
import org.junit.jupiter.api.Test
import kotlin.test.*
class F09F13UpgradeAndIntegrityTest {
 @Test fun optionalDefaultsKeepLegacyEncodingAndLargeLegacyPayloadReadable() {
  val old="{\"type\":\"text\",\"text\":\"old\",\"fontSizePt\":12.0,\"fontColor\":-16777216,\"isRtl\":false,\"runs\":[]}"
  assertEquals(old,CoreCodec.encodeToJson<ObjectPayload>(TextPayload("old")))
  val image=ImagePayload(AssetId("legacy"),20000,20000)
  assertNotNull(CoreCodec.decodeFromCbor<ObjectPayload>(CoreCodec.encodeToCbor<ObjectPayload>(image)).getOrNull())
  val text=TextPayload("x".repeat(1000001))
  assertEquals(text,CoreCodec.decodeFromCbor<ObjectPayload>(CoreCodec.encodeToCbor<ObjectPayload>(text)).getOrNull())
 }
 @Test fun descriptorMetadataTamperingIsRejectedWithoutChangingState() {
  val state=DocumentState.createInitial(DocumentId("integrity"))
  val page=state.pages.single().copy(metadata=PageMetadata(bookmarked=true))
  val plan=EditPlan(state.version,mutations=listOf(UpdatePageDescriptor(page)),gesture=GestureId("metadata"),label=UndoLabel("metadata"))
  val applied=applyPlan(state,validatePlan(state,plan,Limits()).getOrNull()!!).getOrNull()!!
  val original=DocumentState.createInitial(DocumentId("integrity"))
  val tampered=applied.writeSet.copy(plan=plan.copy(mutations=listOf(UpdatePageDescriptor(page.copy(metadata=PageMetadata(bookmarked=false))))))
  assertTrue(replayWriteSet(original,tampered).isFailure)
  assertEquals(Revision(0),original.version.revision)
 }
 @Test fun nestedPayloadBytesAreAccounted() {
  val text="x".repeat(100000)
  val obj=SceneObject(ObjectId("sticky"),SpaceId("space-1"),LayerId("layer-1"),DocRect(0.0,0.0,100.0,100.0),StickyPayload(0,text,richText=TextPayload(text)))
  assertTrue(obj.estimatedMemoryBytes()>=400000)
  val tape=obj.copy(payload=TapePayload(0,points=List(10000){DocPoint(it.toDouble(),0.0)}))
  assertTrue(tape.estimatedMemoryBytes()>=160000)
 }
}
