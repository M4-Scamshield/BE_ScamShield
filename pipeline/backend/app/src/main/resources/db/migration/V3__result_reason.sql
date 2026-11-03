-- agent 2AS(JEV → Gemini)의 탐지 근거. 의심(Yes)일 때만 있고, 아니면 null.
-- 경고(alert)는 result_id 로 이 행을 가리키므로 alert 에는 따로 두지 않는다.
alter table analysis_result add column reason text null after message;
