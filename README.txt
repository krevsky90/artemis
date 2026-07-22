ПРОБЛЕМА 1:
    при отправке Order в JMS (artemis):
    2026-07-10T18:27:02.286Z ERROR 1 --- [order-service] [nio-8081-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: org.springframework.jms.support.converter.MessageConversionException: Cannot convert object of type [com.krev.entity.Order] to JMS message. Supported message payloads are: String, byte array, Map<String,?>, Serializable object.] with root cause
    org.springframework.jms.support.converter.MessageConversionException: Cannot convert object of type [com.krev.entity.Order] to JMS message. Supported message payloads are: String, byte array, Map<String,?>, Serializable object.
РЕШЕНИЕ:
    Spring JMS пытается превратить его в JMS Message.
    По умолчанию JmsTemplate умеет следующие типы (см enum org.springframework.jms.support.converter.MessageType)
        String
        byte[]
        Map<String, ?>
        Serializable object

    Root cause: record Order должен быть Serializable!!
    НО если просто написать Order implements Serializable, то
    по умолчанию JMS ObjectMessage использует Java Serialization, это плохо, т.к.
        1) зависимость от версии Java
        2) невозможно нормально читать сообщения другими языками.
    ПОЭТОМУ отправляем JSON:
        Java Object --- Jackson --- JSON String --- TextMessage JMS --- Artemis
    Для этого:
        создать JmsConfig с MappingJackson2MessageConverter

ПРОБЛЕМА 2:
    переименовал БД постгреса в helm postres.yaml,
    в кубере сделал helm uninstall postgres -n infra,
    поставил заново инфру - а БД inventory_db не создалась. И по-прежнему имеется orders_db
РЕШЕНИЕ:
    после helm uninstall еще нужно дополнительно УДАЛИТЬ PVC!

=============================================

КАК сохранить конфиги artemis в своем проекте и в дальшейшем artemis подхватывал именно их:
1) скопировать дефолтные конфиги артемиса в локальный проект:
    docker cp artemis:/var/lib/artemis-instance/etc ./broker/config
2.1) примонтировать локальную папку с конфигами в виде отдельного волюма
2.2) удалить локально те конфиги, к-ые не планируем менять, оставив лишь
    broker.xml, artemis-users.properties, artemis-roles.properties
2.3) примонтировать конкретные файлы из broker/config, а также docker-volume для данных
volumes:
  - ./broker/config/broker.xml:/var/lib/artemis-instance/etc/broker.xml
  - ./broker/config/artemis-users.properties:/var/lib/artemis-instance/etc/artemis-users.properties
  - ./broker/config/artemis-roles.properties:/var/lib/artemis-instance/etc/artemis-roles.properties
  - artemis-data:/var/lib/artemis-instance/data

NOTE:
Путь сообщения от order-service до inventory-service:
    order-service

    OrderEventCreated
     |
     |
    JmsTemplate
     |
     |
    Jackson converter
     |
     |
    TextMessage
     |
     |
    Artemis
     |
     |
    orders.queue
     |
     |
    inventory-service
     |
     |
    @JmsListener
     |
     |
    OrderEventCreated

Этап 3: добавить 5 консюмеров:
Спринг создает:
    DefaultMessageListenerContainer
        ↓
        Connection
        ↓
            Session1
            ↓
            Consumer1
            ↓
            Thread1


            Session2
            ↓
            Consumer2
            ↓
            Thread2

Путь сообщения, когда 1 консюмер:
    orders.queue  -> JMS Consumer -> Thread -> @JmsListener

Путь сообщение, когда 5 консюмеров (т.е. concurrency = 5)
    orders.queue
          │
     ┌────┴────┐
      Consumer1
        ...
      Consumer5
     └────┬────┘
          │
    Spring Listener Container

    То есть создается 5 независимых JMS Consumer.
    И каждый имеет
        1) собственную Session;
        2) собственный MessageConsumer;
        3) собственный поток.

В логах inventory-service:
    Thread=org.springframework.jms.JmsListenerEndpointContainer#0-3 finished order=8b9ba3ce-52c5-44e6-b973-0dccd4aacca5
    где #0 - номер JMS listener-a (т.к. над классом консюмера указан листенер)
        -3 - номер консюмера, отвечающего этому листенеру

Этап 5. Ack from consumer
    Обычный flow:
        Получить сообщение -> Преобразовать JSON -> Вызвать @JmsListener
        -> Метод завершился без Exception -> Spring отправил ACK -> Artemis удалил сообщение

    Если произошло исключение:
        Получить сообщение -> Вызвать @JmsListener -> RuntimeException -> ACK НЕ отправлен
        -> Artemis считает сообщение необработанным -> Через некоторое время отправляет снова

    NOTE: ACK отправляется DefaultMessageListenerContainer-ом (или JmsListenerEndpointContainer в новых версиях Spring).
        т.е. по умолчанию НЕ моим кодом.

    Типы ACK-ов:
        1) AUTO_ACKNOWLEDGE - спринг решает сам
        2) CLIENT_ACKNOWLEDGE - программист решает message.acknowledge();
            Можно вызвать позже. Можно не вызвать.
            Можно обработать несколько сообщений и подтвердить их одной операцией.
        3) DUPS_OK_ACKNOWLEDGE
            ACK отправляется не сразу. Spring/JMS Provider может копить подтверждения.
            Это быстрее. Но возможны дубликаты после сбоя. Используется РЕДКО.
        4) SESSION_TRANSACTED
            Вообще нет ACK!
            Есть commit() или rollback()
            т.е. commit = ACK

    Механизм работы:
    т.к. ACK — это часть спецификации JMS.
        1) Spring вызывает JMS API.
        2) JMS-клиент отправляет ACK брокеру.
        3) Artemis реализует эту спецификацию и принимает ACK.

    ВОПРОС: В какой именно момент Spring отправляет ACK? До выхода из метода, после выхода из метода или после возврата управления в контейнер?
    ОТВЕТ:
        Spring отправляет ACK после того, как метод полностью завершился и управление вернулось обратно в контейнер JmsListenerEndpointContainer
        Детальная (примерная) схема:
            Получение сообщения (JmsListenerEndpointContainer -> MessageConsumer.receive())
            (здесь сообщение нах-ся в статусе In Delivery или Delivered)
                    │
            Десериализация (TextMessage -> MappingJackson2MessageConverter -> OrderCreatedEvent)
                    │
            Вызов @JmsListener (т.е. моего метода consume(event))
                    │
            МОЙ Метод полностью завершился
                    │
            Управление вернулось контейнеру Spring
                    │
            Контейнер принимает решение:
                    │
               ┌────┴────┐
            Успех     Exception
               │         │
            ACK     Recovery/Rollback
               │         │
            Удалить   Повторная
            сообщение доставка

        т.е. упрощенно:
            Message message = consumer.receive();
            Object payload = converter.fromMessage(message);
            try {
                listener.invoke(payload);
                acknowledge();  // или  session.commit();
            } catch (Exception e) {
                session.rollback();
            }

        NOTE: НЕЛЬЗЯ перехватывать и НЕ пробрасывать исключения в consume-методе. Иначе будет отправлен ACK!

Этап 5.2. Headers / message properties
    Заголовок / свойство	Для чего используется
    JMSMessageID	Уникальная идентификация сообщения, логирование. НЕ меняется при redelivery!
    JMSCorrelationID	Request/Reply, связь запроса и ответа
    JMSRedelivered	Определение повторной доставки (true/false)
    JMSXDeliveryCount	Логика повторных попыток, мониторинг, алерты
    JMSReplyTo	Асинхронный ответ на сообщение
    JMSPriority	Приоритетная обработка
    JMSExpiration	TTL сообщений
    JMSDestination	Диагностика и универсальные обработчики
    JMSTimestamp	Аудит и измерение задержек

Этап 6. DLQ settings
    Чтобы сделать ретрай консюмера 3 раза с интервалом 2 сек,
    нужно изменить broker.xml
        redelivery-delay = 2s
        max-delivery-attempts = 3
    затем скопировать локальный измененный файл на сервис артемиса
        docker cp ./broker/config/broker.xml artemis:/var/lib/artemis-instance/etc/broker.xml
    и рестартануть его:
        docker compose restart artemis

    NOTE: чтобы наглядно проверить, что идут ретраи, можно временно внедрить в consumer поле jakarta.jms.Message
        и взять у него проперти JMSXDeliveryCount
        Тогда в логах inventory-service увидим deliveryCount=1, потом = 2, потом =3.
        Пример:
        2026-07-13T18:38:10.432Z  INFO 1 --- [inventory-service] [ntContainer#0-7] com.krev.consumer.OrderConsumer          : deliveryCount=3

    NOTE: в отличие от кафка, в artemis есть DLQ по умолчанию. Имеет смысл рассмотреть 3 сценария:
        1) max-delivery-attempts=-1 — сообщение бесконечно переотправляется и никогда не попадает в DLQ. растет JMSXDeliveryCount.
            используют для каких-то супер важных сообщений.
            ОПАСНО, т.к. если в очереди poison message (например, с throw new RuntimeException()), то вся очередь будет бесконечно ждать.
            ИМЕННО из-за poison message и придумали DLQ!

            Пример настройки для конкретного паттерна очередей (в broker.xml):
                <address-setting match="orders.#">
                    <max-delivery-attempts>-1</max-delivery-attempts>
                </address-setting>
        2) max-delivery-attempts=3 — сообщение после трех ошибок уходит в DLQ. Это классический сценарий, к-ый я реализовал
        3) Большой redelivery-delay, например 30000 мс. Тогда станет заметно, что очередь не "долбит" Consumer непрерывно, а выдерживает паузу между попытками.