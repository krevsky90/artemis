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

NOTE: setConcurrency("3-6") - значит, что default = min = 3, max = 6
        а setConcurrency("6") - значит, что default = min = 1! а max = 6

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

Этап 7. Transactions
Есть JMS transactions - чтобы в одной JMS session атомарно:
    1) читать сообщения из артемиса
    2) как-то обрабатывать из java-кодом
    3) отправлять сообщения в артемис
    Применение: полезно для кейса "прочитал и отправил дальше".
        Т.к. если, например, отправка упадет, то и факт чтения останется без ACK-a (а точнее, commit-a)
    Как настраивается: см inventory-service/src/main/java/com/krev/config/JmsConfig.java # jmsListenerContainerFactory
        строкой factory.setSessionTransacted(true);

Есть транзакционности других систем (БД, Кафка и пр).

ПРОБЛЕМА: JMS session и транзакция, например, Postgres НИКАК НЕ СИНХРОНИЗИРОВАНЫ!
    поэтому если консюмер читает сообщение и сохранение в БД падает,
    то на JMS сессию это не влияет, и может быть отправлен ACK в очередь.
РЕШЕНИЕ: идемпотентный консюмер (Inbox pattern), т.е. защита от повторной обработки входящих сообщений;
        + транзакционность всех операций с одной и той же БД.
    Пример: см inventory-service/src/main/java/com/krev/service/OrderProcessor.java
    т.е.
        1) читаем сообщение
        2) пытаемся сохранить event_id в таблицу. Если сохранение такого id уже было, то событие уже обработано, ничего не делаем. Иначе - сохраняем.
        3) сохраняем данные из event-а в inventory таблицу. Или как-то по-другому влияем на inventory таблицу пришедшим событием.

Этап 8. Topic vs Queue
info:
!!! https://chat.qwen.ai/c/fdfff862-50d0-49ce-b4c3-16a456904d7f
https://chatgpt.com/g/g-p-69de2569c3f481918b01d49dddd12f4c-swe/c/6a611648-b4e4-83ed-9dc7-ded84f7d0a5b

Если Queue, то сообщение получает один из консюмеров этой очереди.
Если Topic, то каждый подписчик получает свою копию сообщения.

Что где используется:
	Queue - когда есть одна задача, которую должен выполнить один исполнитель (Кто первый взял — тот сделал)
	Topic - когда есть событие: "что-то произошло" и много заинтересованных систем.

NOTE: сообщение не хранится в Topic. Topic — это скорее "точка маршрутизации".

Таким образом, в Artemis/JMS обычно:
	Topic
	  |
	Subscription queues
	  |
	Consumers

И @JmsListener почти всегда висит именно на конечной очереди.
Это важное отличие от Kafka, где consumer group сама является механизмом подписки. В JMS/Artemis эта логика больше вынесена в broker

Определения:
	Durable подписка - создается очередь, связанная с топиком. Если консюмер отключается, очередь копит соообщения и ждет, пока он подключится. Тогда он прочитает, сообщения удалятся из очереди.
	Shared подписка - НЕ указывается clientId, из очереди могут читать несколько консюмеров. Используется load balancing. Чтение масштабируется.

Как задать создание ConnectionFactory:
1) через application.yaml
	Пример:
		spring:
		  jms:
			pub-sub-domain: true # Обязательно для топиков
			listener:
			  session:
				transacted: true	# включаем транзакционность JMS
			  # may be client-id: inventory-service-1
			subscription:
			  durable: true # Включаем durable режим

2) Через @Bean в Java Config-e (класс JmsConfig)
	Пример:
		@Bean
		public DefaultJmsListenerContainerFactory topicListenerFactory(
				ConnectionFactory connectionFactory,
				MessageConverter converter
		) {
			DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

			factory.setConnectionFactory(connectionFactory);
			factory.setMessageConverter(converter);
			// factory.setPubSubDomain(true); // Можно оставить, но spring.jms.pub-sub-domain=true в YAML уже это делает
			factory.setConcurrency("3-6");  // i.e. default = min = 3, max = 6

			factory.setSubscriptionDurable(true);

			factory.setSessionTransacted(true);

			return factory;
		}

NOTE: конфигурация в @Bean перетирает/важнее конфигурации из application.yaml!

Best practice: более гибко - задавать конфигурацию в @Bean и потом указывать название бина в консюмере в containerFactory
	Пример:
		@JmsListener(destination = "${messaging.topics.orders}",
            subscription = "${messaging.subscriptions.inventory}",
            containerFactory = "topicListenerFactory")
    public void consume(OrderCreatedEvent event, Message message) { ... }

	потому что теор-ски один и тот же сервис может слушать и топики (нужна фабрика с PubSubDomain = true), и обычные очереди (т.е. PubSubDomain = false)
	Аналогичные бины для создания разных фабрик задаются и в сервисе-продюсере.

Типы очередей:
1) CORE address/queue - она durable, создается при прописывании ее в broker.xml
    Пример:
        <addresses>
            <address name="orders.topic">
                <multicast>
                    <queue name="inventory-subscription"/>
                </multicast>
            </address>
        	...
        </addresses>

    ИЛИ создается продюсером, когда вызывается код jmsTemplate.convertAndSend(topicName, ...)
        НО для этого нужны настройки в broker.xml
            <address-settings>
        		<address-setting match="#">
        			<auto-create-addresses>true</auto-create-addresses>
        			<auto-create-queues>true</auto-create-queues>
        		</address-setting>
            </address-settings>
        Иначе будет ошибка (для топика, например):
            Destination orders.topic does not exist или AMQ229017: Address does not exist

2) JMS Topic/Subscription - создается автоматически, когда Spring вызывает JMS API, глядя на настройки @JmsListener продюсера/консюмера
    NOTE: если JMS очередь/subscription была создана кодом как durable, то при удалении JmsListener-a очередь ОСТАНЕТСЯ в Артемисе, не удалится!!!

ДОПУСТИМ:
	топик и очереди заданы в broker.xml
		broker.xml
		<addresses>
          <address name="orders.topic">
            <multicast>
               <queue name="inventory-subscription"/>
            </multicast>
          </address>
		   ...
		 </addresses>

Типы подписок:
1. (старая, JMS 1.1) Classic (Non-shared) durable
	Очередь имеет свойства, указанные в определении durable.
	Читать из этой очереди может только один подписчик. У него должен быть абсолютно уникальным в рамках всего брокера Artemis.clientId (указан в application.yaml сервиса).
	ЕСЛИ, например, в сервисе создать несколько консюмеров, и у них будут одинаковые clientId, то вторая копия "выбьет" первую из брокера, а будет ошибка вида:
			"message":"Could not refresh JMS Connection for destination 'orders.topic' - ...
			Cause: clientID=inventory-service was already set into another connection

	ПРОБЛЕМА 1: переименовали clientId - очередь осталась висеть навсегда
	ПРОБЛЕМА 2: чтобы масштабировать приложение, для каждого инстанса сервиса нужен уникальный clientId. Типа
		spring:
		 jms:
		   listener:
			 client-id: inventory-service-${random.uuid} # Или ${HOSTNAME} в Kubernetes
	ПРОБЛЕМА 3: если задать параметры для ConnectionFactory не в application.yaml, а в JmsConfig в виде бина, то ошибка
		setClientID call not supported on proxy for shared Connection. Set the 'clientId' property on the SingleConnectionFactory instead
		хз, как обойти это ограничение, поэтому ограничился application.yaml

	Код:
		application.yaml
			jms:
			  listener:
				session:
				  transacted: true
			  pub-sub-domain: true
			  subscription-durable: true
			  client-id: inventory-service

		Консюмер:
			@Component
			@Slf4j
			public class TemporaryNotificationConsumer {
				@JmsListener(destination = "${messaging.topics.orders}",
						subscription = "${messaging.subscriptions.notification}")
				public void consume(OrderCreatedEvent event) { ... }

	ИТОГО: Classic Durable consumer используют для легаси систем, где осталось JMS 1.1. Или когда очень важно знать, кто именно подключился и считал сообщение.

2. Shared durable
	Очередь имеет свойства, указанные в определении durable.
	clientId задавать НЕ нужно.
	Потому что в JMS 2.0 для Shared Durable client-id не обязателен, так как брокер идентифицирует подписку только по её имени.
	Тогда консюмеры могут по очереди (load balancing) читать сообщения из очереди топика и затем (после ack) сообщение удаляется из очереди топика.
	С точки зрения кода - см "Classic (Non-shared) durable", НО НУЖНО
	а) удалить clientId
	б) просеттить subscription-shared
	Тогда Spring Boot автоматически использует JMS 2.0 Shared Durable Consumer.
	NOTE: если не сделать б), то Spring будет юзать JMS 1.1, и требовать clientId!

	В JmsConfig создать
		@Bean
		public DefaultJmsListenerContainerFactory topicListenerFactory(
				ConnectionFactory connectionFactory,
				MessageConverter converter
		) {
			DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

			factory.setConnectionFactory(connectionFactory);
			factory.setMessageConverter(converter);
			factory.setPubSubDomain(true);
			factory.setSubscriptionDurable(true); // Durable subscription
			factory.setSubscriptionShared(true);  // Shared subscription
	//        factory.setClientId("inventory-service");	// do NOT set clientId!

			// turn on JMS transactions
			factory.setSessionTransacted(true);

			return factory;
		}

	Удалить из application.yml секцию spring.jms

	Указать в консюмерах сервиса containerFactory = "topicListenerFactory". Т.е.
		@JmsListener(destination = "${messaging.topics.orders}",
            subscription = "${messaging.subscriptions.inventory}",
            containerFactory = "topicListenerFactory")

	NOTE: в JmsProperties НЕТ свойства subscription-shared, поэтому его можно задать только через setSubscriptionShared в @Bean !

	ИТОГО: На данный момент это ОЧЕНЬ ПОПУЛЯРНЫЙ И МАСШТАБИРУЕМЫЙ подход! По сути напоминает работу с кафкой, только та не удаляет сообщение из очереди после прочтения.

3. Classic (Non-shared) volatile (Non-durable):
    Т.к. очередь non-durable, то:
        1) она создается автоматически при подключении консюмера к топику, ее имя - автосгенеренный UUID
        2) при отлючении консюмера (разрыв TCP соединения очередь автоматически УДАЛЯЕТСЯ, сообщения в ней УДАЛЯЮТСЯ
        3) Если запустить 3 копии сервиса, каждая создаст свою собственную временную очередь.
            Каждая копия получит полную копию всех сообщений (Fan-out / Broadcast).
            Load balancing (разделение нагрузки) здесь не работает.
    Код:
        В broker.xml
            a) указываем только названия топика, а временные очереди создадутся сами
                <address name="orders.topic">
                    <multicast/> <!-- Тип маршрутизации: Multicast (Topic) -->
                </address>
            ??? b) убедиться, что <auto-create-queues>true</auto-create-queues>

        В консюмере указываем ТОЛЬКО топик:
            @JmsListener(destination = "${messaging.topics.orders}")

        В application.yml или JmsConfig-е указываем лишь pub-sub-domain: true
            т.е. subscription-durable, subscription-shared, client-id - удаляем!

    ВОПРОС: почему, несмотря на настройки
        <auto-create-queues>false</auto-create-queues>
        <auto-create-addresses>false</auto-create-addresses>
        в broker.xml,
        Артемис все равно создает новые очереди (часто с UUID-шным именем) для консюмеров?
    ОТВЕТ: потому что настройки по auto-create относятся к CORE очередям и адресам (топикам)
        если внимательно посмотреть на парентовый тэг, то это как раз <core>
        Этот же протокол указан для Producer-а в Web Console Артемиса.
        То есть auto-create = false запрешает создавать топики/очереди, в которые будет писать producer!
        Но НЕ запрещает создавать non-durable очереди, ИЗ к-ых будут читать консюмеры!
        Таким образом, Артемис создает non-durable JMS subscription queue, а не CORE queue
        Вот если бы, допустим, не было orders.topic, а продюсер делал jmsTemplate.convertAndSend("orders.topic", ...),
        то он бы не смог создать CORE topic и кинул ошибку:
            если речь про топик, то Destination orders.topic does not exist или AMQ229017: Address does not exist

4. Shared volatile (non-durable):
    Т.к. non-durable, то Артемис будет автоматически создавать JMS Subscription queue с именем типа nonDurable.inventory-subscription для соответствующего консюмера,
    и НЕ будет пользоваться CORE-ной очередью inventory-subscription (потому что - см web console - она durable=true)

    Код:
        В broker.xml
            a) указываем только названия топика, а временные очереди создадутся сами
                <address name="orders.topic">
                    <multicast/> <!-- Тип маршрутизации: Multicast (Topic) -->
                </address>

        В консюмере указываем топик + subscription:
                    @JmsListener(destination = "${messaging.topics.orders}",
                                subscription = "${messaging.subscriptions.inventory}",
                                containerFactory = "topicListenerFactory")

        В application.yml или JmsConfig-е указываем
            pub-sub-domain = true и subscription-shared - true

    NOTE: сообщения из non-durable очереди удаляются, когда после отключения последнего консюмера.

------------------
НАСТРОЙКИ ПРОДЮСЕРА:
    кастомизировать бин JmsTemplate topicJmsTemplate, проставив template.setPubSubDomain(true);
    заюзать бин и топик
        @Value("${messaging.topics.orders}")
    	private String topicName;

    	public OrderProducer(@Qualifier("topicJmsTemplate") JmsTemplate jmsTemplate) {
    	    this.jmsTemplate = jmsTemplate;
        }

НАСТРОЙКИ КОНСЮМЕРА:
    см файл "Полная шпаргалка 4 типа подписок на Topic.xlsx"

		NOTE: сочетание clientId + subscription д б УНИКАЛЬНО!


		@JmsListener(destination = "${messaging.topics.orders}",	//это имя топика из broker.xml
            subscription = "${messaging.subscriptions.inventory}",	// это queue name, связанного с топиков
            containerFactory = "topicListenerFactory")				// (опционально) containerFactory - задается имя фабрики из JmsConfig
	NOTE:
		Topic (Address с multicast routing) - это источник сообщений. Producer отправляет сюда: jmsTemplate.convertAndSend("orders.topic", order);
		Subscription (подписка) - каждый подписчик получает свою очередь. например, <queue name="inventory.subscription"/>
	имя subscription уникально ТОЛЬКО в пределах topic-a!

NOTE: несмотря на то, что listener подключается к inventory-subscription, это не самостоятельный Topic, а multicast-очередь, принадлежащая адресу orders.topic.
Именно поэтому такая схема работает в Artemis. Это одна из особенностей реализации JMS в Artemis

-----------------
КАК создать factory для JMS template продюсера и JMS listener консюмера?
Если все просто, и сервис содержит единственную фабрику (например, для отправки в очередь ИЛИ топик), то можно просто задать в application.yaml
spring:
  jms:
    pub-sub-domain: true # true - for topic, false - for queue
    listener:
      session:
        transacted: true # true - to use JMS transaction

Если же требуется более одной фабрики (например, чтобы уметь принимать/отправлять сообщения И в очередь, И в топик),
то нужно писать JmsConfig явно и использовать алиас фабрики:
	для консюмера: @JmsListener(destination = "...", containerFactory = "topicListenerFactory")
	для продюсера используем @Qualifier:
		private final JmsTemplate topicJmsTemplate;

		public OrderProducer(@Qualifier("topicJmsTemplate") JmsTemplate topicJmsTemplate) {
			this.topicJmsTemplate = topicJmsTemplate;
		}
-----------------

Рекомендации по выбору
Сценарий                                                    | Рекомендуемый тип
Современные микросервисы (нужна балансировка + сохранность) | Shared Durable
Микросервисы + очереди в XML (ручное управление)            | Shared Non-Durable
Real-time данные (старые не важны, нужна скорость)          | Classic Non-Durable
Legacy система (JMS 1.1, строгий аудит)                     | Classic Durable

ГЛАВНЫЕ ВЫВОДЫ:
    Shared Durable — лучший выбор для 95% современных задач на Spring Boot + Artemis.
    Classic Durable требует client-id и не поддерживает масштабирование (1 client-id = 1 активное подключение).
    Classic Non-Durable не сохраняет сообщения и не поддерживает балансировку (каждый консюмер получает всё).
    Shared Non-Durable сохраняет сообщения только если очередь прописана в broker.xml.
    Для любой подписки с атрибутом subscription в @JmsListener нужно указывать spring.jms.pub-sub-domain: true.

Этап 9. Selectors
info: https://chatgpt.com/g/g-p-69de2569c3f481918b01d49dddd12f4c/c/6a611648-b4e4-83ed-9dc7-ded84f7d0a5b

ИДЕЯ: selector помогает фильтровать сообщения.
    Producer добавляет JmsProperty, в котором хранятся selector-ы, к отправляемому сообщению (см OrderProducer).
        т.е. само сообщение (его тело) НЕ меняется!
    В зависимости от значения проперти сообщение будет попадать в ту или иную очередь топика.
    Т.е. продюсер отправляет сообщения в один топик, а куда оно дальше пойдет - конфигурится не в продюсере, а на уровне брокера.
    Селекторы могут быть строковыми, числовыми, логическими операциями.
    Селекторы могут иметь любые названия и любые значения.

    ПРИМЕНЕНИЕ:
        VIP-клиенты → отдельный консюмер.
        Сообщения для региона EU → отдельный сервис.
        Retry-сообщения → отдельный обработчик.
        Тип события (eventType='ORDER_CREATED', eventType='ORDER_CANCELLED') → разные обработчики.

        Без селекторов пришлось бы делать множество топиков и очередей только ради простой фильтрации.

    Пример:
                         orders.queue
                              │
              ┌───────────────┴───────────────┐
              │                               │
        Selector: priority='HIGH'      Selector: priority='LOW'
              │                               │
        HighPriorityConsumer          LowPriorityConsumer

    NOTE:
        Для JMS shared subscription несколько listeners с одной и той же подпиской, но разными селекторами — допустимая модель
        (при соблюдении правил совместимости селекторов).
        Однако на практике её используют нечасто, потому что она усложняет понимание поведения подписки.

1) ЕСЛИ Core-очередь, то в broker.xml добавляем filter и ребутаем Артемис,
    Пример:
    <address name="orders.topic">
        <multicast>
            <queue name="notification-subscription">
                <filter string="notificationType = 'HIGH_PRICE'"/>
            </queue>
        </multicast>
    </address>

    а в JmsListener-е НЕ ПИШЕМ selector!
            @JmsListener(destination = "${messaging.topics.orders}",
                    subscription = "${messaging.subscriptions.notification}",
                    containerFactory = "topicListenerFactory")
            public void consume(OrderCreatedEvent event) {
                log.info("HighPriceNotificationConsumer has received event = {}", event);
            }

    NOTE: если же написать селектор в JmsListener-e, то он просто НЕ БУДЕТ РАБОАТЬ!
        Он никак не применится к уже созданной core-ной очереди.

2) ЕСЛИ Jms-очередь (т.е. НЕ прописана в broker.xml), то фильтр пишем в JmsListener-e:
        @JmsListener(destination = "${messaging.topics.orders}",
                subscription = "high-price-subscription",   // JMS (but not core) queue
                selector = "notificationType = 'HIGH_PRICE'",
                containerFactory = "topicListenerFactory")
        public void consume(OrderCreatedEvent event) {
            log.info("HighPriceNotificationConsumer has received event = {}", event);
        }

        Если high-price-subscription очередь будет создана (например, она non-durable),
        то колонка Filter в Web console будет отображать созданное условие.

Если очередь shared, и несколько консюмеров имеют одинаковые селекторы, то они будут по очереди (load balancing) брать сообщения из очереди.

Этап 10. Message Groups
ИДЕЯ:
    Message Group отвечает: Какому конкретному consumer'у закрепить последовательность сообщений?
    Если, например, событие по Order 15 попадает консюмеру А,
    то все последующие сообщения этой группы (Order 15) автоматически отправляются тому же consumer'у А.
        Order 15
            CREATED
            RESERVED
            PACKED
            SHIPPED
    В таком случае последовательность событий по заказу сохранится и порядок обработки - тоже. Т.е. будет правильным.

    В продюсере добавляем свойство JMSXGroupID.
    Например,
        jmsTemplate.convertAndSend(topicName, event, message -> {
            message.setStringProperty("JMSXGroupID", event.orderId().toString());
            return message;
        });

    Консюмер не меняется. Никаких специальных настроек не требуется. Всю работу делает Artemis.
    Он железно маппит "одна Message group -> один consumer (пока консюмер жив)"

    Один consumer может обслуживать несколько Message group.
    Пока группа активна, два consumer'а одновременно никогда не будут обрабатывать сообщения одной и той же группы.
    Именно это и обеспечивает сохранение порядка сообщений внутри группы при параллельной обработке разных групп.

Message group используют обычно для очередей, а НЕ топиков.
Потому что идея - разгрести общую работу (очередь ордеров, например) в параллель, обрабатывая события по каждому ордеру (или др "partition key") в исходной последовательности.

ВОПРОС: Можно ли использовать Message group для очередей топиков?
ОТВЕТ: можно, НО т.к. JMSXGroupID задается в продюсере при отправке сообщений в топик, то получится,
    что все очереди-подписки будут иметь один и тот же ключ (а-ля "partition key"),
    это не всегда удобно по смыслу каждой очереди.
РЕШЕНИЯ:
    1) Выбирать наиболее важный ключ
    2) Не использовать Message Groups в Topic
        Например, orders.topic только рассылает события, а каждый сервис внутри уже делает своё распределение.
            Или просто несколько внутренних очередей.
    3) Разделить события: вместо одного orders.topic делают
            inventory.topic
            notification.topic
            analytics.topic
        И Producer публикует три разных сообщения. Тогда можно каждому сообщению поставить свой JMSXGroupID
        НО ПЛОХО: producer начинает знать о потребителях
    4) НЕ использовать Topic для команд
       Это САМЫЙ ПОПУЛЯРНЫЙ подход в реальных системах.
       Есть событие OrderCreated
       Его читает оркестратор (или routing service) и отправляет команды:
           ReserveInventory
           SendNotification
           UpdateAnalytics
       Каждая команда идёт в свою очередь:
           inventory.queue
           notification.queue
           analytics.queue
       И уже там можно использовать:
       Для Inventory: JMSXGroupID = warehouseId
       Для Notification: JMSXGroupID = customerId
       Для Analytics: вообще без Message Groups
       Каждая очередь живёт по своим правилам.

ИТОГО: именно поэтому Message group обычно не сочетают с топиками.

Применительно к очереди (см мой код, ${messaging.queues.orders}), если сделать, например, 5 консюмеров (concurrency = 5-5), но НЕ использовать message group по product,
    то любой из консюмеров обрабатывает сообщения с product = KREV_PRODUCT_1:
        {"@timestamp":"2026-07-27T14:03:48.42087346Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notificat
        ionConsumer has received eventId = bcc9d229-dfbf-4ce0-a97a-60669f977460 with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:49.14547539Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-4","message":"Notificat
        ionConsumer has received eventId = 90adc1ca-f0a5-407c-86fc-2465649f4245 with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:49.805318874Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-5","message":"Notifica
        tionConsumer has received eventId = 5518850e-ce5d-491b-8036-498c3e08255f with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:50.459021115Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notifica
        tionConsumer has received eventId = 3d824a64-05ac-4017-bf99-d5204a19c971 with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:51.085358147Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-4","message":"Notifica
        tionConsumer has received eventId = af2ac209-636f-4f13-98ca-300f2cb4ea80 with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:51.727237209Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-5","message":"Notifica
        tionConsumer has received eventId = 5acc6510-07e6-4183-a28a-6d77ddcf1735 with product = KREV_PRODUCT_1"}
        {"@timestamp":"2026-07-27T14:03:52.408165041Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notifica
        tionConsumer has received eventId = 97eb19c1-9f70-45f0-b278-0a60ba53f5a7 with product = KREV_PRODUCT_1"}

А если включить concurrency = 3-3 и message.setStringProperty("JMSXGroupID", orderCreatedEvent.product());, то
    {"@timestamp":"2026-07-27T16:01:33.828545965Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-3","message":"Notifica
    tionConsumer has received eventId = f0797737-a172-4a2b-ae2d-b34b92e33657 with product = KREV_PRODUCT_1"}
    {"@timestamp":"2026-07-27T16:01:36.836947108Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-3","message":"Notifica
    tionConsumer has received eventId = 5b99bf9c-defa-4df2-8090-a4a943765084 with product = KREV_PRODUCT_1"}
    {"@timestamp":"2026-07-27T16:01:36.874541803Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-2","message":"Notifica
    tionConsumer has received eventId = e6c44161-7b55-470c-861c-af14a7582715 with product = KREV_PRODUCT_2"}
    {"@timestamp":"2026-07-27T16:01:39.018660419Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notifica
    tionConsumer has received eventId = 5339e28e-308b-422b-8c98-095df52a7967 with product = KREV_PRODUCT_4"}
    {"@timestamp":"2026-07-27T16:01:39.842876921Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-3","message":"Notifica
    tionConsumer has received eventId = d91f2fad-62a4-4782-9179-e23dc2317da4 with product = KREV_PRODUCT_1"}
    {"@timestamp":"2026-07-27T16:01:39.87842119Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-2","message":"Notificat
    ionConsumer has received eventId = 21c5912e-c778-4541-a8cf-2cfb13037371 with product = KREV_PRODUCT_2"}
    {"@timestamp":"2026-07-27T16:01:42.025580304Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notifica
    tionConsumer has received eventId = 2da663de-5f32-49b3-99f1-fcf1d3261947 with product = KREV_PRODUCT_4"}
    {"@timestamp":"2026-07-27T16:01:42.849869603Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-3","message":"Notifica
    tionConsumer has received eventId = 566629ac-56f6-4d37-9d75-62740b36eba2 with product = KREV_PRODUCT_3"}
    {"@timestamp":"2026-07-27T16:01:45.02998513Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-1","message":"Notificat
    ionConsumer has received eventId = f75648a4-af4d-4c4b-9645-27bbe95b7107 with product = KREV_PRODUCT_4"}
    {"@timestamp":"2026-07-27T16:01:45.137173011Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-2","message":"Notifica
    tionConsumer has received eventId = f966e4f3-672a-4f06-aff5-0b5617eb0044 with product = KREV_PRODUCT_5"}
    {"@timestamp":"2026-07-27T16:01:45.854245101Z","service":"notification-service","level":"INFO","thread":"org.springframework.jms.JmsListenerEndpointContainer#0-3","message":"Notifica
    tionConsumer has received eventId = ceb207e0-fbec-4523-a78b-8489c5e20711 with product = KREV_PRODUCT_3"}

Т.е. видно, что
    JmsListenerEndpointContainer#0-1:
    	KREV_PRODUCT_4
    JmsListenerEndpointContainer#0-2:
    	KREV_PRODUCT_2
    	KREV_PRODUCT_5
    JmsListenerEndpointContainer#0-3:
    	KREV_PRODUCT_1
    	KREV_PRODUCT_3

--------- Artemis vs Kafka ---------
Kafka: максимальный параллелизм в одной consumer group ограничен количеством партиций (т.к. число работающих консюмеров = числу партиций)
Artemis: максимальный параллелизм определяется количеством consumer'ов и независимых Message Groups; отдельного ограничения, аналогичного числу партиций, нет.
	т.е. Максимальный параллелизм = min(число активных Message Groups, число доступных Consumer'ов)
	* активных, потому что если будет группа с 1млн сообщений, и остальные группы с 1тыс, то эти остальные группы будут проставивать (проблема равномерного распределения нагрузки есть и тут).

Artemis — доставить команду конкретному исполнителю.
Kafka — опубликовать факт для всех заинтересованных.

Зачастую Artemis выбирают там, где нужно доставить команду, и НЕ использовать возможность воспроизведение сообщения еще раз (типа подвинуть оффсет).
Также у Artemis есть встроенная DLQ, в отличие от Кафка.
JMS очень хорошо интегрирован с транзакциями.

В целом у Artemis есть:
	Session Transacted;
	XA (при необходимости);
	redelivery;
	DLQ;
	Message Groups;
	request/reply.

ЧТО выбрать? => Что является первичной сущностью моей системы?
Если это:
	команды;
	workflow;
	очереди задач;
	обработка заявок;
	интеграция сервисов;
то JMS-брокер вроде Artemis зачастую оказывается более естественным выбором.

Если же это:
	поток событий;
	аналитика;
	аудит;
	CDC;
	Data Lake;
	возможность многократно перечитывать историю;
то Kafka практически всегда подходит лучше.

Если обобщить
	Kafka — это распределённый журнал событий (distributed log).
		Её задача — долго хранить огромный поток событий и позволять разным потребителям читать его независимо, в том числе повторно.
	Artemis — это брокер сообщений (message broker).
		Его задача — надёжно и эффективно доставить сообщение нужному обработчику, после чего сообщение обычно становится ненужным.

Этап 11 Scheduled Message.

Что отложенная доставка сообщений (Scheduled Messages) очень часто встречается в реальных проектах.
Например:
повторить оплату через 15 минут;
отправить email завтра в 9:00;
начать обработку через 30 секунд;

Сообщение сразу попадает в очередь. НО консюмеру отдается с задержкой.
Spring ничего не планирует. Всё делает сам Artemis.

Как работает: Artemis понимает специальные свойства JMS-сообщения.
	_AMQ_SCHED_DELIVERY - это timestamp (в миллисекундах), после которого сообщение станет доступным.

Имплементация:
Продюсер:
	public void send(OrderCreatedEvent event) {
		jmsTemplate.convertAndSend(queueName, event, message -> {
			message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + 30_000);
			return message;
		});
	}

В Web Console:
	в очереди orders.queue увидишь сообщение.
	Оно будет числиться в очереди, но не будет выдано consumer'у, пока не наступит время доставки.
	Через 30 секунд оно исчезнет из очереди (если consumer его успешно обработал).

Пример 1:
если сконфигурить delay в продюсере
	event1 → 5 секунд
	event2 → 30 секунд
	event3 → 60 секунд
и пульнуть REST-ом event3, event2, event1,
то они придут в консюмер в порядке event1, event2, event3

Пример 2: Scheduler + Message Group
    отправляем сообщения для KREV_PRODUCT_2 с задержкой 30с
    отправляем сообщения для KREV_PRODUCT_1 без задержки, ЗА ИСКЛЮЧЕНИЕМ сообщения с price = 100.00
    Код:

    public void send(OrderCreatedEvent orderCreatedEvent) {
            jmsTemplate.convertAndSend(queueName, orderCreatedEvent, message -> {
                long delay = 0L;
                if ("KREV_PRODUCT_1".equalsIgnoreCase(orderCreatedEvent.product())
                        && BigDecimal.valueOf(100.00).compareTo(orderCreatedEvent.price()) == 0) {
                        delay = 15_000L;
                } else if ("KREV_PRODUCT_2".equalsIgnoreCase(orderCreatedEvent.product())) {
                    delay = 30_000L;
                }

                message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + delay);
                message.setStringProperty("JMSXGroupID", orderCreatedEvent.product());

                return message;
            });
        }

    concurrency of consumer = 2

    Шлем:
        event1: KREV_PRODUCT_2
        event2: KREV_PRODUCT_2
        event3: KREV_PRODUCT_1 price=100.00
        event4: KREV_PRODUCT_1 price=105.00
        event5: KREV_PRODUCT_2

    Тогда:
        Consumer 1:
            получает сообщения только с KREV_PRODUCT_2 и через 30 сек после из отправки (до тез пор они хранятся в очереди, но недоступны)
        Consumer 2:
            получает сообщения только с KREV_PRODUCT_1, причем:
                event4
                event3
    NOTE: ПОТОМУ ЧТО Artemis применяет Message Groups только к сообщениям, которые уже доступны для доставки!!!
        т.е. Message Groups гарантируют порядок только среди сообщений, одновременно находящихся в очереди доставки.
        А отложенное сообщение (_AMQ_SCHED_DELIVERY) еще не находится в очереди доставки.
        Это не баг, потому что scheduler работает ДО помещения сообщения в очередь.

Пример 3. Scheduled + Priority
    NOTE: Priority учитывается только среди сообщений, которые уже доступны для доставки!

    Как проставить приоритет:
        message.setJMSPriority(N);
        N = 1 - min priority
        N = 9 - max priority

    Пример кода:
        jmsTemplate.convertAndSend(queueName, event, message -> {
            message.setLongProperty("_AMQ_SCHED_DELIVERY", scheduledTime);
            if ("HIGH".equals(event.product())) {
                message.setJMSPriority(9);
            } else {
                message.setJMSPriority(1);
            }

            return message;
        });

Пример 4. Scheduled + TTL
Выделяют
1) Свойства, которые относятся к содержимому сообщения. Они задаются через Message
	Примеры: JMSXGroupID, _AMQ_SCHED_DELIVERY, пользовательские свойства, селекторы).
2) JMS-заголовки. Их формирует брокер. Они задаются через JmsTemplate или MessageProducer во время вызова send().
	Это параметры отправки, которыми управляет JMS-провайдер.
	Примеры:
		Delivery Mode (PERSISTENT / NON_PERSISTENT)
		Priority (0...9)
		Time To Live (TTL)
		(в JMS 2.0 также DeliveryDelay)

Свойство explicitQosEnabled - расшифровывается как: Explicit Quality of Service Enabled
	т.е. "Явно использовать параметры Quality of Service при отправке сообщения."

В JMS под QoS понимают параметры, влияющие на доставку сообщения - см список в "JMS-заголовки"

ЕСЛИ explicitQosEnabled = false (по умолчанию):
	то Spring вызывает producer.send(message);
	и все значения (TTL, Priority, DeliveryMode) берутся из настроек брокера или MessageProducer.
NOTE: а настройки в JmsTemplate типа template.setTimeToLive(...); и т д ИГНОРИРУЮТСЯ!

ЕСЛИ explicitQosEnabled = true:
	то Spring начинает вызывать перегруженный метод: producer.send(message, deliveryMode, priority, timeToLive);

ИТОГО:
	explicitQosEnabled = false → используй настройки брокера/JMS MessageProducer-а.
	explicitQosEnabled = true → используй QoS, заданный в JmsTemplate.

Для наглядности в консюмере можно добавить параметр Message message в сигнатуру метода consume и вывести свойства
	log.info("JMSTimestamp={}", message.getJMSTimestamp());
    log.info("JMSExpiration={}", message.getJMSExpiration());
	log.info("JMSPriority={}", message.getJMSPriority());


ВОПРОС: что такое JMS MessageProducer? и как задавать в нем настройки?
ОТВЕТ: это JMS класс, работа с которым скрыта Spring-ом с помощью JmsTemplate.
		В целом, JmsTemplate специально скрывает работу с Session и MessageProducer.
		На каждый вызов convertAndSend() он сам создает (или берет из пула) Session и MessageProducer, отправляет сообщение и освобождает ресурсы.

		Если очень хочется настроить именно MessageProducer, то нужно отказаться от convertAndSend() и использовать execute():
		Пример:
			jmsTemplate.execute(session -> {
				Destination destination = session.createQueue(queueName);

				MessageProducer producer = session.createProducer(destination);

				producer.setPriority(9);
				producer.setTimeToLive(10_000);
				producer.setDeliveryMode(DeliveryMode.PERSISTENT);

				TextMessage message = session.createTextMessage("Hello");

				producer.send(message);

				return null;
			});

НО так обычно никто не делает. Только исключительные случаи, когда надо тонко настроить отправку.
При этом, если работать с MessageProducer-ом, то настройки в JmsTemplate уже не применятся: они просто не используются в этом пути отправки.

ВОПРОС: что такое "настройки брокера"?
ОТВЕТ: broker.xml — это совсем другой уровень.
	Он не задает QoS отправляемого сообщения. Он определяет поведение брокера:
		DLQ;
		ExpiryQueue;
		paging;
		max-delivery-attempts;
		redelivery-delay;
		auto-create;
		security и т.д.

		TTL, Priority и DeliveryMode обычно приходят от клиента (producer), а не из broker.xml.

ВОПРОС: что такое ExpiryQueue?
ОТВЕТ: это очередь, в которую попадают сообщения с истекшим TTL.

Эксперимент: TTL = 10s, _AMQ_SCHED_DELIVERY = 30s.
    Код:
        JmsConfig продюсера:
            template.setExplicitQosEnabled(true);
            template.setTimeToLive(10_000);

        OrderProducer:
            public void send(OrderCreatedEvent orderCreatedEvent) {
                jmsTemplate.convertAndSend(queueName, orderCreatedEvent, message -> {
                    message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + 30_000);
                    return message;
                });
            }

	Результат: сообщение 30с хранится в очереди, а когда становится доступным, консюмер его НЕ обрабатывает, т.к. TTL уже истек,
		и сообщение отправляется в ExpiryQueue. В ней можно посмотреть свойства сообщения:
	| Свойство                 | Значение        | Что означает                                                                                            |
	| ------------------------ | --------------- | ------------------------------------------------------------------------------------------------------- |
	| `__AMQ_CID`              | `517ec71f-...`  | ID JMS-соединения (Connection ID), через которое сообщение было отправлено. Используется самим Artemis. |
	| `_AMQ_ORIG_ROUTING_TYPE` | `1`             | Исходный тип маршрутизации. `1` = ANYCAST (Queue), `0` = MULTICAST (Topic).                             |
	| `_AMQ_SCHED_DELIVERY`    | `1785270031162` | Время (Unix epoch, мс), **не раньше которого** сообщение можно доставить потребителю.                   |
	| `_AMQ_ACTUAL_EXPIRY`     | `1785270032277` | Момент, когда Artemis **фактически признал сообщение просроченным** и переместил его в `ExpiryQueue`.   |
	| `_AMQ_ORIG_MESSAGE_ID`   | `4302397906`    | ID исходного сообщения до его копирования в `ExpiryQueue`. Очень полезно для диагностики.               |

	NOTE: сообщение оказалось в ExpiryQueue не в момент истечения TTL, а при первой попытке его реально обработать.
		Потому что Scheduled Message хранится во внутреннем scheduler'е Artemis.
		До наступления _AMQ_SCHED_DELIVERY сообщение вообще не находится в очереди доставки.
		Поэтому брокер не проверяет TTL каждую секунду.
		т.е. логика работы:
			1) дождаться Scheduled Delivery;
			2) только потом проверить TTL;
			3) если TTL уже истек — сразу отправить в ExpiryQueue.

На практике используют Schedule механизм для
1) Postponed Retry:
    OrderProducer - просто отправляет сообщение
    NotificationConsumer - читает сообщение, пытается послать его через EmailService, к-ый недоступен и кидает exception
    в случае exception-a NotificationConsumer вызывает RetryProducer, передавая в него исходный эвент.
    RetryProducer снова засунул НОВОЕ сообщение с тем же event-ом в ту же очередь (messaging.queues.orders), к-ую читает NotificationConsumer,
    поставив
        _AMQ_SCHED_DELIVERY = currentTime + 10s (например)
        retryCount++
    NOTE: если retry >= 5, то RetryProducer перестает пытаться.
        BEST PRACTICE: НЕ посылать сообщение в DLQ. А отправлять в отдельную бизнес-очередь (типа notification.retry.failed).
            Системную DLQ обычно оставляют для действительно инфраструктурных сбоев
                (например, ошибки десериализации, проблемы с брокером, неожиданные исключения в обработчике),
                а бизнес-ошибки обрабатывают через собственные очереди.

2) Автоматическая отмена бронирования
    Пользователь оформил заказ. На оплату дается 30 минут.
    После CreateOrder OrderService делает две вещи.
    1. Сохраняет заказ. status = CREATED
    2. Планирует отмену
        @Service
        @RequiredArgsConstructor
        public class OrderTimeoutProducer {
            private final JmsTemplate jmsTemplate;

            @Value("${messaging.queues.cancel-orders}")
            private String queue;

            public void scheduleCancellation(UUID orderId) {
                jmsTemplate.convertAndSend(queue, orderId, message -> {
                    message.setLongProperty(
                            "_AMQ_SCHED_DELIVERY",
                            System.currentTimeMillis() + 30 * 60_000);
                    return message;
                });
            }
        }
    Через 30 минут Consumer получает сообщение.
        @Component
        @RequiredArgsConstructor
        public class CancelOrderConsumer {
            private final OrderRepository repository;

            @JmsListener(destination = "${messaging.queues.cancel-orders}")
            public void consume(UUID orderId) {
                Order order = repository.findById(orderId).orElseThrow();

                if (order.getStatus() == CREATED) {
                    order.setStatus(CANCELLED);
                    repository.save(order);
                    log.info("Order cancelled");
                }
            }
        }

    Если клиент оплатил через 10 минут, то OrderService просто меняет статус с CREATED -> PAID,
        и CancelOrderConsumer ничего не делает
    NOTE: дешевле принять неактуальное событие и ничего не сделать, чем искать такое событие, чтобы его удалить

Этап 12 TTL.
Кейсы:
    1) TTL во время обработки сообщения
        TTL = 5 секунд;
        consumer начинает обработку через 2 секунды;
        обработка занимает 20 секунд (Thread.sleep(20000)).

        ВОПРОС: удалит ли Artemis сообщение прямо во время обработки?
        ОТВЕТ: нет. Как только сообщение выдано consumer'у, TTL больше не проверяется. Оно либо ACK'нется, либо откатится.

    2) TTL + rollback
       TTL = 5 секунд;
       consumer получил сообщение;
       7 секунд его обрабатывал;
       бросил исключение;
       rollback.

       Код:
            Producer's JmsConfig:
                template.setTimeToLive(5_000);
            NotificationConsumer:
                log.info("START {}", Instant.now());

                Thread.sleep(7000L);
                log.info("THROW {}", Instant.now());
                log.info("JMSExpiration={}", Instant.ofEpochMilli(message.getJMSExpiration()));
                log.info("deliveryCount={}", message.getIntProperty("JMSXDeliveryCount"));
                throw new JMSException("NotificationConsumer failed KREV");

       ВОПРОС: сообщение вернется в очередь или сразу уйдет в ExpiryQueue?
       ОТВЕТ: сразу уйдет в ExpiryQueue.
            т.е. t = 0     Producer -> Queue
                 t ≈ 0     Consumer получил сообщение
                 t = 5 c   TTL истек
                 t = 9 c   Consumer бросил исключение
                            ↓
                         Rollback
                            ↓
                 Artemis пытается вернуть сообщение в Queue
                            ↓
                 Видит, что TTL уже истек
                            ↓
                 Перемещает сообщение в ExpiryQueue

       NOTE: если сообщение прочитано консюмером, НО НЕ обработано, то его статус = delivering
            и оно значится в колонке Messages очереди, из к-ой его прочитали

    2.2) Producer:
            template.setTimeToLive(15_000);
        и
        consumer:
            Thread.sleep(7000);
            throw new JMSException("boom");
        broker.xml
            <redelivery-delay>2000</redelivery-delay>
            <max-delivery-attempts>3</max-delivery-attempts>

        Тогда
            0 c    первая доставка
            7 c    rollback
            9 c    вторая доставка
            16 c   rollback
            К моменту второго rollback TTL (15 секунд) уже истечет, и сообщение уйдет в ExpiryQueue, не дожидаясь третьей попытки.

        3) TTL + Redelivery
            TTL = 20 сек
            redelivery-delay = 10 сек
            consumer:
            1 попытка -> exception
            через 10 секунд
            2 попытка -> exception
            еще через 10 секунд
            TTL уже закончился.

            Код:
                в JmsTemplate продюсера:
                    template.setExplicitQosEnabled(true);
                    template.setTimeToLive(20_000);

                в broker.xml
                    <address-setting match="#">
                        <redelivery-delay>10000</redelivery-delay>
                        <max-delivery-attempts>5</max-delivery-attempts>
                        ...

                в консюмере:
                    в JmsConfig:
                        factory.setSessionTransacted(true);

                    в consume(..):
                        log.info("JMSTimestamp={}", message.getJMSTimestamp());
                        log.info("JMSExpiration={}", message.getJMSExpiration());
                        log.info("deliveryCount={}", message.getIntProperty("JMSXDeliveryCount"));
                        throw new JMSException("boom");

                NOTE: redelivery наступает, если sessionTransacted= true и консюмер бросает Exception

            ОТВЕТ: после 2х попыток сообщение уходит в ExpiryQueue

        4) NOTE: template.setTimeToLive(0); значит, что TTL отсутствует, сообщение не умрет.

        5) ВОПРОС: почему Вызывать message.setJMSExpiration(...) в продюсере НЕЛЬЗЯ, и это поле должен устанавливать JMS Provider (Artemis).
           ОТВЕТ: интерфейс jakarta.jms.Message используется и для создания сообщения клиентом, и для уже полученного сообщения.
                У него есть общий набор геттеров и сеттеров, но спецификация JMS определяет,
                какие поля может устанавливать приложение, а какие — только провайдер.

                Приложение МОЖЕТ:
                    1) устанавливать свои свойства:
                        message.setStringProperty(...);
                        message.setIntProperty(...);
                    2) устанавливать некоторые JMS-заголовки (например, JMSReplyTo, JMSType)
                Приложение НЕ МОЖЕТ:
                    устанавливать системные заголовки (JMSMessageID, JMSTimestamp, JMSExpiration),
                    их устанавливает провайдер при отправке.
                    ЕСЛИ попытаться проставить системные заголовки через приложение, то значения будут ПРОИГНОРИРОВАНЫ.

                ВОПРОС: почему так устроено API?
                ОТВЕТ: Потому что один и тот же объект Message проходит через несколько стадий:
                    1) приложение создает сообщение;
                        и может сеттить проперти в сообщение
                    2) JMS-провайдер дописывает системные заголовки;
                    3) consumer читает уже готовое сообщение.
                    Поэтому интерфейс содержит сеттеры, но спецификация говорит,
                        что часть из них предназначена для использования самим провайдером, а не пользовательским кодом.