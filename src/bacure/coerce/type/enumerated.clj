(ns bacure.coerce.type.enumerated
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]])
  (:import com.serotonin.bacnet4j.service.confirmed.DeviceCommunicationControlRequest$EnableDisable
           (com.serotonin.bacnet4j.type.enumerated AbortReason
                                                   AccessAuthenticationFactorDisable
                                                   AccessCredentialDisable
                                                   AccessCredentialDisableReason
                                                   AccessEvent
                                                   AccessPassbackMode
                                                   AccessUserType
                                                   AccessZoneOccupancyState
                                                   Action
                                                   AuthenticationFactorType
                                                   AuthenticationStatus
                                                   BackupState
                                                   BinaryPV
                                                   DeviceStatus
                                                   DoorAlarmState
                                                   DoorSecuredStatus
                                                   DoorStatus
                                                   DoorValue
                                                   EngineeringUnits
                                                   ErrorClass
                                                   ErrorCode
                                                   EventState
                                                   EventType
                                                   FileAccessMethod
                                                   LifeSafetyMode
                                                   LifeSafetyOperation
                                                   LifeSafetyState
                                                   LightingInProgress
                                                   LightingOperation
                                                   LightingTransition
                                                   LockStatus
                                                   LoggingType
                                                   Maintenance
                                                   MessagePriority
                                                   NodeType
                                                   NotifyType
                                                   ObjectType
                                                   Polarity
                                                   ProgramError
                                                   ProgramRequest
                                                   ProgramState
                                                   PropertyIdentifier
                                                   RejectReason
                                                   Reliability
                                                   RestartReason
                                                   SecurityLevel
                                                   Segmentation
                                                   ShedState
                                                   SilencedState
                                                   VtClass
                                                   WriteStatus)))


;; TODO: not sure how to use enumerated-converter here. halp.
(defmethod clojure->bacnet :enable-disable
  [_ boolean-state]

  (if boolean-state
    (. DeviceCommunicationControlRequest$EnableDisable enable)
    (. DeviceCommunicationControlRequest$EnableDisable disable)))

(c/enumerated-converter AbortReason)
(c/enumerated-converter AccessAuthenticationFactorDisable)
(c/enumerated-converter AccessCredentialDisable)
(c/enumerated-converter AccessCredentialDisableReason)
(c/enumerated-converter AccessEvent)
(c/enumerated-converter AccessPassbackMode)
(c/enumerated-converter AccessUserType)
(c/enumerated-converter AccessZoneOccupancyState)
(c/enumerated-converter Action)
(c/enumerated-converter AuthenticationFactorType)
(c/enumerated-converter AuthenticationStatus)
(c/enumerated-converter BackupState)
(c/enumerated-converter BinaryPV)
(c/enumerated-converter DeviceStatus)
(c/enumerated-converter DoorAlarmState)
(c/enumerated-converter DoorSecuredStatus)
(c/enumerated-converter DoorStatus)
(c/enumerated-converter DoorValue)
(c/enumerated-converter EngineeringUnits)
(c/enumerated-converter ErrorClass)
(c/enumerated-converter ErrorCode)
(c/enumerated-converter EventState)
(c/enumerated-converter EventType)
(c/enumerated-converter FileAccessMethod)
(c/enumerated-converter LifeSafetyMode)
(c/enumerated-converter LifeSafetyOperation)
(c/enumerated-converter LifeSafetyState)
(c/enumerated-converter LightingInProgress)
(c/enumerated-converter LightingOperation)
(c/enumerated-converter LightingTransition)
(c/enumerated-converter LockStatus)
(c/enumerated-converter LoggingType)
(c/enumerated-converter Maintenance)
(c/enumerated-converter MessagePriority)
(c/enumerated-converter NodeType)
(c/enumerated-converter NotifyType)
(c/enumerated-converter ObjectType)
(c/enumerated-converter Polarity)
(c/enumerated-converter ProgramError)
(c/enumerated-converter ProgramRequest)
(c/enumerated-converter ProgramState)
(c/enumerated-converter PropertyIdentifier)
(c/enumerated-converter RejectReason)
(c/enumerated-converter Reliability)
(c/enumerated-converter RestartReason)
(c/enumerated-converter SecurityLevel)
(c/enumerated-converter Segmentation)
(c/enumerated-converter ShedState)
(c/enumerated-converter SilencedState)
(c/enumerated-converter VtClass)
(c/enumerated-converter WriteStatus)
