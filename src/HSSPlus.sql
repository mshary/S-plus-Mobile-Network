DROP TABLE IF EXISTS `pgw_info`;

CREATE TABLE `pgw_info` (
  `apn` bigint(20) NOT NULL,
  `gw_ip` varchar(20) DEFAULT NULL,
  `dispatch_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`apn`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;


DROP TABLE IF EXISTS `ue_info`;
CREATE TABLE `ue_info` (
  `key` bigint(20) NOT NULL DEFAULT '0',
  `imsi` bigint(20) DEFAULT NULL,
  `msisdn` bigint(20) DEFAULT NULL,
  `nt` varchar(16) DEFAULT 'UMTS',
  `tai` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`key_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

