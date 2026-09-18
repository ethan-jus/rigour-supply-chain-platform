const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const {test} = require("node:test");
const {randomUUID} = require("node:crypto");

const fixturePath = path.join(__dirname, "sales-checkin-recovery.test.cjs");
const fixtureSource = fs.readFileSync(fixturePath, "utf8");
const fixture = new Module(fixturePath);
fixture.filename = fixturePath; fixture.paths = Module._nodeModulePaths(__dirname);
fixture._compile(fixtureSource.slice(0, fixtureSource.indexOf("let checks = 0;")) + "\nmodule.exports = {harness};", fixturePath);

function ready() {
    const h=fixture.exports.harness(), segmentId=randomUUID();
    h.api.state.completed=true;
    Object.assign(h.api.state.submission,{serverId:"original-record",status:"SUBMITTED"});
    h.api.state.submission.audioSegments=[{segmentId,uploadState:"LOCAL",originalFilename:"原录音.wav"}];
    h.file=Object.assign(new Blob(["original audio bytes"]),{name:"原录音.wav"});
    h.api.state.files.audio=[{segmentId,file:h.file}];
    h.api.setRequest(async()=>({id:"original-record",status:"SUBMITTED",uploadedMedia:["storefront-photo"],
        photoIds:[h.photoId],audioSegmentIds:[]}));
    h.window.SalesCheckinDraftStore.saveMedia=async()=>{};
    h.segmentId=segmentId; h.pickerCalls=0;
    h.element("#audio-file").click=()=>{h.pickerCalls++;};
    return h;
}
async function reload(h) {
    const snapshot=h.api.snapshotDraft();h.api.state.files.audio=[];
    h.api.restoreDraft(snapshot);
    h.window.SalesCheckinDraftStore.mediaFor=async()=>[{mediaId:`audio:${h.segmentId}`,file:h.file,filename:h.file.name}];
    await h.api.openSavedDraft({owner:"t1:sales1",snapshot},false);
}
function renderCard(h) {
    let card;
    h.element("#audio-preview-template").content={firstElementChild:{cloneNode(){
        card=h.element("rendered-card");
        card.querySelector=selector=>h.element("rendered-card "+selector);
        return card;
    }}};
    h.api.renderAudioSegmentsActual();
    return selector=>card.querySelector(selector);
}

test("specific HTTP 400 audio content reasons survive reload and choose replacement without another PUT", async()=>{
    for(const reason of ["EMPTY_FILE","FILE_TOO_LARGE","IMAGE_FILE","NO_AUDIO_TRACK","VIDEO_TRACK","UNRECOGNIZED_FORMAT"]){
        const h=ready();let writes=0;
        h.api.setUpload(async()=>{writes++;throw Object.assign(new Error("检测未识别这份录音"),
            {status:400,payload:{reason,requestId:"audit-request-1"}});});
        await h.api.supplementCurrentEvidence();await reload(h);
        const segment=h.api.state.submission.audioSegments[0];
        assert.equal(segment.uploadErrorStatus,400);assert.equal(segment.uploadErrorReason,reason);
        assert.equal(segment.uploadErrorRequestId,"audit-request-1");assert.match(segment.errorMessage,/检测未识别/);
        await h.api.supplementCurrentEvidence();assert.equal(writes,1,reason);
        assert.equal(h.element("#success-retry-button").textContent,"处理待补附件");
        await h.api.retryAudioSegment(h.segmentId);
        assert.equal(h.pickerCalls,1,reason);assert.equal(writes,1,reason);assert.equal(h.api.state.files.audio[0].file,h.file);
    }
});

test("HTTP 415 without a reason also requires a replacement file", async()=>{
    const h=ready();let writes=0;
    h.api.setUpload(async()=>{writes++;throw Object.assign(new Error("格式不支持"),{status:415});});
    await h.api.supplementCurrentEvidence();await reload(h);await h.api.retryAudioSegment(h.segmentId);
    assert.equal(h.api.state.submission.audioSegments[0].uploadErrorStatus,415);
    assert.equal(h.pickerCalls,1);assert.equal(writes,1);
});

test("READ_FAILED and temporary or unknown business failures remain retryable after reload", async()=>{
    for(const [status,reason] of [[400,"READ_FAILED"],[401,"BUSINESS_RULE"],[409,"BUSINESS_RULE"],[400,"BUSINESS_RULE"],[400,"NEW_UNKNOWN_REASON"]]){
        const h=ready();let writes=0;
        h.api.setUpload(async()=>{writes++;if(writes===1)throw Object.assign(new Error("暂时无法读取"),{status,payload:{reason}});
            return {id:"original-record",kind:"audio",segmentId:h.segmentId};});
        await h.api.supplementCurrentEvidence();await reload(h);
        assert.equal(h.api.state.submission.audioSegments[0].uploadErrorReason,reason==="NEW_UNKNOWN_REASON"?"BUSINESS_RULE":reason);
        await h.api.retryAudioSegment(h.segmentId);
        assert.equal(h.pickerCalls,0);assert.equal(writes,2);assert.equal(h.api.state.submission.audioSegments[0].uploadState,"UPLOADED");
        assert.equal(h.api.state.submission.audioSegments[0].uploadErrorReason,null);
    }
});

test("failure download uses the retained original Blob and replacement clears stale rejection metadata", async()=>{
    const h=ready();
    Object.assign(h.api.state.submission.audioSegments[0],{uploadState:"ERROR",uploadErrorStatus:400,
        uploadErrorReason:"UNRECOGNIZED_FORMAT",uploadErrorRequestId:"request-old",errorMessage:"未识别格式"});
    const controls=renderCard(h),download=controls("[data-audio-download]");
    assert.equal(download.hidden,false);assert.equal(download.href,controls("audio").src);
    assert.equal(download.download,"原录音.wav");assert.equal(controls("[data-audio-retry]").textContent,"更换文件");
    assert.equal(await (await fetch(download.href)).text(),"original audio bytes");
    const replacement=Object.assign(new Blob(["replacement bytes"]),{name:"新录音.wav"});
    h.api.attachAudioFile(h.segmentId,replacement);await h.api.state.persistence;
    const segment=h.api.state.submission.audioSegments[0];
    assert.equal(segment.uploadErrorReason,null);assert.equal(segment.uploadErrorRequestId,null);
    assert.equal(segment.uploadErrorStatus,null);assert.equal(segment.errorMessage,"");
    assert.equal(h.api.state.files.audio[0].file,replacement);
    assert.equal(renderCard(h)("[data-audio-download]").hidden,true);
});

test("unsaved local audio exposes original download while ordinary and confirmed audio do not", async()=>{
    const h=ready();assert.equal(renderCard(h)("[data-audio-download]").hidden,true);
    h.api.state.unsavedMedia.add(`t1:sales1/${h.api.state.submission.clientSubmissionId}/audio:${h.segmentId}`);
    assert.equal(renderCard(h)("[data-audio-download]").hidden,false);
    h.api.state.unsavedMedia.clear();h.api.state.submission.audioSegments[0].uploadState="UPLOADED";
    assert.equal(renderCard(h)("[data-audio-download]").hidden,true);
});

test("restored rejection metadata is bounded and does not preserve arbitrary status, reason, or HTML text", ()=>{
    const h=ready(),snapshot=h.api.snapshotDraft(),segment=snapshot.submission.audioSegments[0];
    Object.assign(segment,{uploadErrorStatus:9999,uploadErrorReason:"arbitrary",uploadErrorRequestId:"<script>",errorMessage:"<html>raw proxy error</html>"});
    h.api.restoreDraft(snapshot);const restored=h.api.state.submission.audioSegments[0];
    assert.equal(restored.uploadErrorStatus,null);assert.equal(restored.uploadErrorReason,"BUSINESS_RULE");
    assert.equal(restored.uploadErrorRequestId,null);assert.equal(restored.errorMessage,"");
    snapshot.submission.audioSegments[0].errorMessage="错".repeat(500);h.api.restoreDraft(snapshot);
    assert.equal(h.api.state.submission.audioSegments[0].errorMessage.length,240);
});

test("a lifecycle receipt arriving after evidence editing begins keeps the editor and playing audio node", async()=>{
    for(const received of [false,true]){
        const h=ready();
        Object.assign(h.api.state.submission.audioSegments[0],{uploadState:"ERROR",uploadErrorStatus:400,
            uploadErrorReason:"NO_AUDIO_TRACK",errorMessage:"检测未识别音轨"});
        const controls=renderCard(h),player=controls("audio");player.currentTime=0.4;player.paused=false;
        const card=h.element("rendered-card");
        h.element("#audio-preview-list").querySelectorAll=selector=>selector==='[data-audio-segment]'?[card]:[];
        let release;h.api.setRequest(()=>new Promise(resolve=>{release=resolve;}));
        const pending=h.api.recoverInterruptedSubmission();
        // The user enters evidence editing while a prior foreground/picker check is still pending.
        h.api.state.editingEvidence=true;h.element("#visit-panel").hidden=false;h.element("#success-panel").hidden=true;
        release({id:"original-record",status:"SUBMITTED",uploadedMedia:["storefront-photo"],
            photoIds:[h.photoId],audioSegmentIds:received?[h.segmentId]:[]});
        await pending;await h.api.state.persistence;
        assert.equal(h.api.state.editingEvidence,true);assert.equal(h.element("#visit-panel").hidden,false);
        assert.equal(h.element("#success-panel").hidden,true);assert.equal(controls("audio"),player);
        assert.equal(player.currentTime,0.4);assert.equal(player.paused,false);assert.equal(h.uploaded.length,0);
        assert.equal(controls("[data-audio-retry]").hidden,received);
        assert.match(controls("[data-audio-status]").textContent,received?/已上传/:/检测未识别/);
    }
});
