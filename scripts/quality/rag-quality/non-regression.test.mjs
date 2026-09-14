import assert from 'node:assert/strict';
import { compareNonRegression } from './non-regression.mjs';
const identity={fixtureHash:'fixture',model:'local',modelDigest:'sha',inferenceSettings:{context:12288},repositories:[{id:'repo',fingerprint:'fp',activeIndex:'index'}]};
const item={id:'case',question:'Explain entry point',domain:'code',dimensions:{language:'java'},status:'passed',citation:{recall:1,precision:1},evidence:{coverage:1},claims:{requiredCoverage:1},codeGrounding:{fileCoverage:1,symbolCoverage:1,implementationCoverage:1},followUp:{quality:1},gates:{implementationBodies:true,forbiddenClaims:true},latency:{observedMs:100}};
const report={schema:'learnbot.quality.rag-score.v1',passed:false,summary:{totalCases:1,scoredCases:1,skippedCases:0,passedCases:1,citationRecall:1,citationPrecision:1,evidenceCoverage:1,requiredClaimCoverage:1,expectedFileCoverage:1,expectedSymbolCoverage:1,implementationBodyCoverage:1,followUpQuality:1,hallucinationRiskFlags:0},results:[item]};
assert.deepEqual(compareNonRegression(report,structuredClone(report),identity,identity),[]);
for(const mutate of [r=>{r.results=[]},r=>{r.results[0].status='skipped'},r=>{r.results[0].status='failed'},r=>{r.results.push(item)},r=>{r.results[0].claims.requiredCoverage=.5},r=>{r.results[0].codeGrounding.implementationCoverage=.5},r=>{r.results[0].gates.forbiddenClaims=false},r=>{r.results[0].question='different'},r=>{r.results[0].latency.observedMs=121},r=>{delete r.results[0].gates},r=>{delete r.summary.expectedFileCoverage}]) {
 const changed=structuredClone(report);mutate(changed);assert.ok(compareNonRegression(report,changed,identity,identity).length);
}
assert.ok(compareNonRegression(report,report,identity,{...identity,modelDigest:'changed'}).length);
assert.ok(compareNonRegression(report,report,null,null).length);
console.log('non-regression comparison contracts passed');
