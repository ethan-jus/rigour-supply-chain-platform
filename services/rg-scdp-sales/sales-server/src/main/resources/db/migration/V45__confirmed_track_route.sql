ALTER TABLE sales_location_session
    ADD COLUMN confirmed_track BOOLEAN NOT NULL DEFAULT FALSE COMMENT '会话使用经运动确认的轨迹';

ALTER TABLE sales_location_point
    ADD COLUMN route_path VARCHAR(520) NULL COMMENT '此点确认的按时序排列的原始定位事件编号';
